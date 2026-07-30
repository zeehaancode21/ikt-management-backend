package com.example.backend.controller;

import com.example.backend.entity.*;
import com.example.backend.repository.*;
import com.example.backend.security.VaultTokenUtil;
import com.example.backend.service.EncryptionService;
import com.example.backend.service.TotpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.crypto.CipherInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Everything in here requires the caller to be OWNER, and everything that
 * touches actual decrypted content additionally requires a valid "vault
 * token" obtained from /vault/unlock, which itself requires a correct
 * 6-digit TOTP code from the owner's authenticator app. The vault token
 * expires after 10 minutes — after that, a fresh phone code is required
 * again, even if the owner is still logged in normally.
 *
 * Flow for the owner:
 *   1. POST /vault/setup        -> one-time: get QR/secret, scan with phone
 *   2. POST /vault/confirm      -> enter first code to activate 2FA
 *   3. POST /vault/unlock       -> enter a code any time -> get vault token (10 min)
 *   4. GET  /vault/employee/{u} -> X-Vault-Token header -> decrypted bank details + doc list
 *   5. GET  /vault/download/{id}-> X-Vault-Token header -> decrypted file bytes
 */
@RestController
@RequestMapping("/vault")
public class VaultController {

    @Autowired private OwnerTotpSecretRepository totpRepo;
    @Autowired private EmployeeDocumentRepository documentRepo;
    @Autowired private EmployeeBankDetailRepository bankRepo;
    @Autowired private VaultAuditLogRepository auditRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EncryptionService encryptionService;
    @Autowired private TotpService totpService;
    @Autowired private VaultTokenUtil vaultTokenUtil;

    @Autowired
    @Qualifier("uploadExecutor")
    private Executor taskExecutor;

    private static final String ISSUER = "IKT Vault";

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

     private boolean isOwner(String username) {
        return userRepo.findByUsername(username)
                .map(u -> {
                    String role = u.getRole();
                    boolean isOwner = role != null && role.trim().equalsIgnoreCase("OWNER");
                    return isOwner;
                })
                .orElseGet(() -> {
                    return false;
                });
    }

    private void requireOwnerOrSelf(String targetUsername) {
        String actor = currentUsername();
        if (!actor.equals(targetUsername) && !isOwner(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to access this employee's data");
        }
    }

    private void requireOwner() {
        String username = currentUsername();
        boolean owner = userRepo.findByUsername(username)
                .map(u -> {
                    String role = u.getRole();
                    return role != null && role.trim().equalsIgnoreCase("OWNER");
                })
                .orElseGet(() -> {
                     return false;
                });
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner access only");
        }
    }

    private void audit(String action, String target, String detail, HttpServletRequest request) {
        VaultAuditLog log = new VaultAuditLog();
        log.setActorUsername(currentUsername());
        log.setAction(action);
        log.setTargetEmployee(target);
        log.setDetail(detail);
        log.setIpAddress(request.getRemoteAddr());
        auditRepo.save(log);
    }

    /** Validates the X-Vault-Token header and returns the owner's username, or throws 401. */
    private String requireValidVaultToken(String vaultToken) {
        String username = vaultTokenUtil.validateAndExtractUsername(vaultToken);
        if (username == null || !username.equals(currentUsername())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Vault session expired or invalid — please re-enter your authenticator code");
        }
        return username;
    }

    // ---------- One-time 2FA setup ----------

    @GetMapping("/2fa-status")
    public Map<String, Boolean> twoFaStatus() {
        requireOwner();
        boolean enabled = totpRepo.findByUsername(currentUsername()).map(OwnerTotpSecret::isEnabled).orElse(false);
        return Map.of("enabled", enabled);
    }

    @PostMapping("/setup")
    public Map<String, String> setup() {
        requireOwner();
        String username = currentUsername();
        Optional<OwnerTotpSecret> existing = totpRepo.findByUsername(username);
        if (existing.isPresent() && existing.get().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "2FA is already enabled. Disable it first if you need to re-pair a device.");
        }

        String secret = totpService.generateSecret();
        OwnerTotpSecret entity = existing.orElseGet(OwnerTotpSecret::new);
        entity.setUsername(username);
        entity.setSecretEnc(encryptionService.encryptText(secret));
        entity.setEnabled(false);
        totpRepo.save(entity);

        String otpAuthUri = totpService.buildOtpAuthUri(secret, username, ISSUER);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("secret", secret); // shown once, for manual entry if QR scan fails
        result.put("otpAuthUri", otpAuthUri); // frontend renders this as a QR code
        return result;
    }

    @PostMapping("/confirm")
    public Map<String, String> confirm(@RequestBody Map<String, String> body) {
        requireOwner();
        String username = currentUsername();
        OwnerTotpSecret entity = totpRepo.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run /vault/setup first"));

        String secret = encryptionService.decryptText(entity.getSecretEnc());
        if (!totpService.verifyCode(secret, body.get("code"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect code");
        }
        entity.setEnabled(true);
        entity.setEnabledAt(java.time.LocalDateTime.now());
        totpRepo.save(entity);
        return Map.of("message", "Two-factor authentication enabled for the vault");
    }

    // ---------- Unlock (per-session, code required every time) ----------

    @PostMapping("/unlock")
    public Map<String, String> unlock(@RequestBody Map<String, String> body, HttpServletRequest request) {
        requireOwner();
        String username = currentUsername();
        OwnerTotpSecret entity = totpRepo.findByUsername(username)
                .filter(OwnerTotpSecret::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Vault 2FA is not set up yet — go to vault settings first"));

        String secret = encryptionService.decryptText(entity.getSecretEnc());
        if (!totpService.verifyCode(secret, body.get("code"))) {
            audit("FAILED_CODE", null, "Incorrect TOTP code on unlock attempt", request);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect code");
        }

        audit("UNLOCK", null, "Vault unlocked for 10 minutes", request);
        String vaultToken = vaultTokenUtil.generateVaultToken(username);
        return Map.of("vaultToken", vaultToken, "expiresInSeconds", "600");
    }

    // ---------- Gated decrypted reads ----------

    @GetMapping("/employee/{username}")
    public Map<String, Object> viewEmployeeVault(@PathVariable String username,
                                                  @RequestHeader("X-Vault-Token") String vaultToken,
                                                  HttpServletRequest request) {
        requireOwner();
        requireValidVaultToken(vaultToken);
        audit("VIEW_EMPLOYEE", username, "Viewed confidential data", request);

        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> docs = new ArrayList<>();
        for (EmployeeDocument doc : documentRepo.findByEmployeeUsername(username)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", doc.getId());
            d.put("docType", doc.getDocType());
            d.put("fileName", doc.getOriginalFileName());
            d.put("contentType", doc.getContentType());
            d.put("uploadedAt", doc.getUploadedAt());
            docs.add(d);
        }
        result.put("documents", docs);

        bankRepo.findByEmployeeUsername(username).ifPresent(bank -> {
            Map<String, String> bankDetails = new LinkedHashMap<>();
            bankDetails.put("accountHolderName", encryptionService.decryptText(bank.getAccountHolderNameEnc()));
            bankDetails.put("accountNumber", encryptionService.decryptText(bank.getAccountNumberEnc()));
            bankDetails.put("ifsc", encryptionService.decryptText(bank.getIfscEnc()));
            bankDetails.put("bankName", encryptionService.decryptText(bank.getBankNameEnc()));
            result.put("bankDetails", bankDetails);
        });

        return result;
    }

    @GetMapping("/download/{docId}")
    public ResponseEntity<StreamingResponseBody> downloadDocument(
            @PathVariable Long docId,
            @RequestHeader("X-Vault-Token") String vaultToken,
            HttpServletRequest request) {

        requireOwner();
        requireValidVaultToken(vaultToken);

        // Fetch document metadata + encrypted blob from DB
        EmployeeDocument doc = documentRepo.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        // Capture values needed inside the lambda before any async work starts
        final String actorUsername   = currentUsername();
        final String targetEmployee  = doc.getEmployeeUsername();
        final String docTypeName     = doc.getDocType().name();
        final String ipAddress       = request.getRemoteAddr();
        final byte[] encryptedBlob   = doc.getEncryptedFileData();

        // Fire audit write off the request thread — response starts without waiting for it
        taskExecutor.execute(() -> {
            VaultAuditLog log = new VaultAuditLog();
            log.setActorUsername(actorUsername);
            log.setAction("DOWNLOAD_DOCUMENT");
            log.setTargetEmployee(targetEmployee);
            log.setDetail(docTypeName);
            log.setIpAddress(ipAddress);
            auditRepo.save(log);
        });

        // Resolve content type once, before the streaming lambda
        final MediaType mediaType = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        // StreamingResponseBody: Spring writes headers immediately, then pumps
        // bytes from the CipherInputStream into the socket in 8 KB chunks.
        // The client receives the first bytes before decryption is fully done.
        StreamingResponseBody body = (OutputStream out) -> {
            try (CipherInputStream cis = encryptionService.decryptStream(encryptedBlob)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = cis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            // IOException / crypto failures propagate naturally; Spring closes
            // the response and the client sees a broken download (correct behaviour
            // for a tampered blob — better than a silent hang).
        };

        // Sanitise the filename to prevent header injection
        String safeFileName = doc.getOriginalFileName() != null
                ? doc.getOriginalFileName().replaceAll("[\\r\\n\"]", "_")
                : "document";

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeFileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                // Tell the client how large the plaintext will be so it can show progress.
                // Plaintext size = blob - 12-byte IV - 16-byte GCM tag
                .header(HttpHeaders.CONTENT_LENGTH,
                        String.valueOf(encryptedBlob.length - 12 - 16))
                .body(body);
    }

    // ---------- Audit log (also vault-gated) ----------

    @GetMapping("/audit-log")
    public List<VaultAuditLog> auditLog(@RequestHeader("X-Vault-Token") String vaultToken) {
        requireOwner();
        requireValidVaultToken(vaultToken);
        return auditRepo.findTop200ByOrderByTimestampDesc();
    }
}