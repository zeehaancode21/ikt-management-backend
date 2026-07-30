package com.example.backend.controller;

import com.example.backend.entity.*;
import com.example.backend.repository.*;
import com.example.backend.service.EncryptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/kyc")
public class KycController {

    @Autowired private EmployeeDocumentRepository documentRepo;
    @Autowired private EmployeeBankDetailRepository bankRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private EncryptionService encryptionService;

    private static final List<DocumentType> REQUIRED_DOC_TYPES = List.of(DocumentType.values());

    private String currentUsername() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return username;
    }

    private boolean isOwner(String username) {
        return userRepo.findByUsername(username)
                .map(u -> {
                    String role = u.getRole();
                    boolean isOwner = role != null && role.trim().equalsIgnoreCase("OWNER");
                    return isOwner;
                })
                .orElse(false);
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
                .orElse(false);
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner access only");
        }
    }

    // ---------- Completeness checklist ----------

    /** Checklist for one employee: which of the 4 file docs + bank details are present. Status only, no content. */
    @GetMapping("/status/{username}")
    public Map<String, Object> getStatus(@PathVariable String username) {
        requireOwnerOrSelf(username);
        return buildStatus(username);
    }

    @GetMapping("/status/me")
    public Map<String, Object> getOwnStatus() {
        return buildStatus(currentUsername());
    }

   private Map<String, Object> buildStatus(String username) {
        Map<String, EmployeeDocument> existing = new HashMap<>();
        for (EmployeeDocument d : documentRepo.findByEmployeeUsername(username)) {
            existing.put(d.getDocType().name(), d);
        }
        boolean hasBankDetails = bankRepo.findByEmployeeUsername(username).isPresent();
        return buildStatusFromData(username, existing, hasBankDetails);
    }

   /**
     * Same output as buildStatus(username), but takes pre-fetched data instead of
     * hitting the DB itself. Lets callers batch-load documents/bank details for
     * MANY employees up front and reuse this pure formatting logic per employee
     * with zero extra queries.
     */
    private Map<String, Object> buildStatusFromData(
            String username,
            Map<String, EmployeeDocument> existingByDocType,
            boolean hasBankDetails) {

        List<Map<String, Object>> checklist = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (DocumentType type : REQUIRED_DOC_TYPES) {
            EmployeeDocument doc = existingByDocType.get(type.name());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docType", type.name());
            item.put("uploaded", doc != null);
            if (doc != null) {
                item.put("id", doc.getId());
                item.put("fileName", doc.getOriginalFileName());
                item.put("uploadedAt", doc.getUploadedAt());
            } else {
                missing.add(type.name());
            }
            checklist.add(item);
        }

        Map<String, Object> bankItem = new LinkedHashMap<>();
        bankItem.put("docType", "BANK_DETAILS");
        bankItem.put("uploaded", hasBankDetails);
        if (!hasBankDetails) missing.add("BANK_DETAILS");
        checklist.add(bankItem);

        int total = checklist.size();
        int complete = total - missing.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("checklist", checklist);
        result.put("missing", missing);
        result.put("complete", complete);
        result.put("total", total);
        result.put("isComplete", missing.isEmpty());
        return result;
    }

    /** Owner-only: completeness overview across every employee. Status only. */
    @GetMapping("/overview")
    public List<Map<String, Object>> overview() {
        requireOwner();
        List<String> usernames = userRepo.findUsernamesByRoles(List.of("USER", "LEAD", "MANAGER"));
        if (usernames.isEmpty()) {
            return new ArrayList<>();
        }

        // Was: 2 queries PER employee (N+1) -> now exactly 2 queries total, regardless of employee count.
        Map<String, Map<String, EmployeeDocument>> docsByUsername = new HashMap<>();
        for (EmployeeDocument d : documentRepo.findByEmployeeUsernameIn(usernames)) {
            docsByUsername
                    .computeIfAbsent(d.getEmployeeUsername(), k -> new HashMap<>())
                    .put(d.getDocType().name(), d);
        }

        Set<String> usernamesWithBankDetails = new HashSet<>();
        for (EmployeeBankDetail b : bankRepo.findByEmployeeUsernameIn(usernames)) {
            usernamesWithBankDetails.add(b.getEmployeeUsername());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String username : usernames) {
            Map<String, EmployeeDocument> existing = docsByUsername.getOrDefault(username, Map.of());
            boolean hasBankDetails = usernamesWithBankDetails.contains(username);
            result.add(buildStatusFromData(username, existing, hasBankDetails));
        }
        return result;
    }

    // ---------- File document upload ----------

    @PostMapping("/documents/{docType}")
    public Map<String, String> uploadOwnDocument(@PathVariable DocumentType docType,
                                                  @RequestParam("file") MultipartFile file) {
        return uploadDocument(currentUsername(), docType, file);
    }

    @PostMapping("/documents/{username}/{docType}")
    public Map<String, String> uploadDocumentFor(@PathVariable String username,
                                                   @PathVariable DocumentType docType,
                                                   @RequestParam("file") MultipartFile file) {
        requireOwner();
        return uploadDocument(username, docType, file);
    }

    private Map<String, String> uploadDocument(String username, DocumentType docType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be 10MB or smaller");
        }

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }

        EmployeeDocument doc = documentRepo.findByEmployeeUsernameAndDocType(username, docType)
                .orElseGet(EmployeeDocument::new);
        doc.setEmployeeUsername(username);
        doc.setDocType(docType);
        doc.setOriginalFileName(file.getOriginalFilename());
        doc.setContentType(file.getContentType());
        doc.setFileSize(file.getSize());
        doc.setEncryptedFileData(encryptionService.encryptBytes(rawBytes));
        doc.setUploadedBy(currentUsername());
        documentRepo.save(doc);

        return Map.of("message", "Uploaded successfully", "docType", docType.name());
    }

    // ---------- Bank details (structured, encrypted fields) ----------

    public record BankDetailsRequest(String accountHolderName, String accountNumber, String ifsc, String bankName) {}

    @PostMapping("/bank-details")
    public Map<String, String> setOwnBankDetails(@RequestBody BankDetailsRequest req) {
        return saveBankDetails(currentUsername(), req);
    }

    @PostMapping("/bank-details/{username}")
    public Map<String, String> setBankDetailsFor(@PathVariable String username, @RequestBody BankDetailsRequest req) {
        requireOwner();
        return saveBankDetails(username, req);
    }

    private Map<String, String> saveBankDetails(String username, BankDetailsRequest req) {
        if (isBlank(req.accountNumber()) || isBlank(req.ifsc()) || isBlank(req.bankName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Account number, IFSC, and bank name are required");
        }
        EmployeeBankDetail bank = bankRepo.findByEmployeeUsername(username).orElseGet(EmployeeBankDetail::new);
        bank.setEmployeeUsername(username);
        bank.setAccountHolderNameEnc(encryptionService.encryptText(req.accountHolderName()));
        bank.setAccountNumberEnc(encryptionService.encryptText(req.accountNumber()));
        bank.setIfscEnc(encryptionService.encryptText(req.ifsc().toUpperCase(Locale.ROOT)));
        bank.setBankNameEnc(encryptionService.encryptText(req.bankName()));
        bank.setUpdatedBy(currentUsername());
        bankRepo.save(bank);
        return Map.of("message", "Bank details saved");
    }

    /** Employees can view their OWN decrypted bank details (it's their own data, no vault token needed). */
    @GetMapping("/bank-details/me")
    public Map<String, String> getOwnBankDetails() {
        String username = currentUsername();
        EmployeeBankDetail bank = bankRepo.findByEmployeeUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No bank details on file"));
        Map<String, String> result = new LinkedHashMap<>();
        result.put("accountHolderName", encryptionService.decryptText(bank.getAccountHolderNameEnc()));
        result.put("accountNumber", encryptionService.decryptText(bank.getAccountNumberEnc()));
        result.put("ifsc", encryptionService.decryptText(bank.getIfscEnc()));
        result.put("bankName", encryptionService.decryptText(bank.getBankNameEnc()));
        return result;
    }

    // ---------- Employee document view/download (their OWN documents) ----------

    /** Employee can view their own document inline */
    @GetMapping("/documents/{docId}/view")
    public ResponseEntity<byte[]> viewOwnDocument(@PathVariable Long docId) {
         String username = currentUsername();
        
        EmployeeDocument doc = documentRepo.findById(docId)
                .orElseThrow(() -> {
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
                });
        
        // Allow if: user owns the document OR user is OWNER
        boolean canAccess = doc.getEmployeeUsername().equals(username) || isOwner(username);
        
        if (!canAccess) {
             throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own documents");
        }
        
        byte[] decryptedData = encryptionService.decryptBytes(doc.getEncryptedFileData());
        MediaType mediaType = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        
        String safeFileName = doc.getOriginalFileName() != null
                ? doc.getOriginalFileName().replaceAll("[\\r\\n\"]", "_")
                : "document";
        
        return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, 
                "inline; filename=\"" + safeFileName + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(decryptedData);
    }

    /** Employee can download their own document */
    @GetMapping("/documents/{docId}/download")
    public ResponseEntity<byte[]> downloadOwnDocument(@PathVariable Long docId) {
        String username = currentUsername();
        EmployeeDocument doc = documentRepo.findById(docId)
                .orElseThrow(() -> {
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
                });
        
        
        // Allow if: user owns the document OR user is OWNER
        boolean canAccess = doc.getEmployeeUsername().equals(username) || isOwner(username);
        
        if (!canAccess) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only download your own documents");
        }
        
        byte[] decryptedData = encryptionService.decryptBytes(doc.getEncryptedFileData());
        MediaType mediaType = doc.getContentType() != null
                ? MediaType.parseMediaType(doc.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        
        String safeFileName = doc.getOriginalFileName() != null
                ? doc.getOriginalFileName().replaceAll("[\\r\\n\"]", "_")
                : "document";
        
       
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                        "attachment; filename=\"" + safeFileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(decryptedData.length))
                .body(decryptedData);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}