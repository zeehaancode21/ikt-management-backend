package com.example.backend.controller;

import com.example.backend.entity.EmployeeProfile;
import com.example.backend.entity.User;
import com.example.backend.repository.EmployeeProfileRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final int ROLE_NAME_MAX_LENGTH = 100;

    @Autowired
    private UserRepository repo;

    @Autowired
    private EmployeeProfileRepository employeeProfileRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * Request body for /auth/register. Mirrors User's public, client-settable
     * fields (username, email, password, role) plus the new optional
     * `roleName` — the admin-set custom display title (e.g. "Senior Checker"),
     * which is stored on EmployeeProfile rather than User since it's a
     * cosmetic display field, not a permission-bearing one.
     */
    public record RegisterRequest(String username, String email, String password, String role, String roleName) {}

    /** Roles an OWNER is allowed to hand out when creating an account. */
    private static final Set<String> ALLOWED_ROLES = Set.of("OWNER", "ADMIN", "MANAGER", "LEAD", "USER");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}._@ -]{2,50}$");
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 72; // BCrypt ignores anything past 72 bytes

    /**
     * Create an employee account — OWNER only.
     *
     * SECURITY: this used to be a public endpoint that trusted the "role" sent by
     * the caller, so anyone on the internet could register themselves as OWNER.
     * It now requires a logged-in OWNER (the Admin Console is the only caller),
     * and the role must be one of ALLOWED_ROLES.
     */
    @PostMapping("/register")
    @PreAuthorize("hasRole('OWNER')")
    public User register(@RequestBody RegisterRequest request) {
        // ---- validate everything BEFORE saving anything ----
        String username = request.username() == null ? "" : request.username().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Username must be 2-50 characters: letters, numbers, spaces, . _ @ -");
        }
        String password = request.password();
        if (password == null || password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be between " + PASSWORD_MIN_LENGTH + " and " + PASSWORD_MAX_LENGTH + " characters.");
        }
        String role = (request.role() == null || request.role().isBlank())
                ? "USER" : request.role().trim().toUpperCase();
        if (!ALLOWED_ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        String trimmedRoleName = null;
        if (request.roleName() != null && !request.roleName().isBlank()) {
            trimmedRoleName = request.roleName().trim();
            if (trimmedRoleName.length() > ROLE_NAME_MAX_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Role name must be " + ROLE_NAME_MAX_LENGTH + " characters or fewer");
            }
        }
        if (repo.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        // ---- create ----
        User user = new User();
        user.setUsername(username);
        user.setEmail(request.email() == null ? null : request.email().trim());
        user.setPassword(encoder.encode(password));
        user.setRole(role);

        // Never return the password hash in the response
        User saved = repo.save(user);
        saved.setPassword(null);

        // Optional custom role name (display title), e.g. "Senior Checker".
        if (trimmedRoleName != null) {
            if (employeeProfileRepository.existsByUsername(username)) {
                employeeProfileRepository.updateRoleName(username, trimmedRoleName, java.time.LocalDateTime.now());
            } else {
                EmployeeProfile profile = new EmployeeProfile();
                profile.setUsername(username);
                profile.setRoleName(trimmedRoleName);
                employeeProfileRepository.save(profile);
            }
        }

        return saved;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody User user) {
        User dbUser = repo.findByUsername(user.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!encoder.matches(user.getPassword(), dbUser.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String token = jwtUtil.generateToken(dbUser.getUsername());

        String roleName = employeeProfileRepository.findRoleNameByUsername(dbUser.getUsername())
                .filter(rn -> !rn.isBlank())
                .orElse(null);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("role", dbUser.getRole() != null ? dbUser.getRole() : "USER");
        response.put("name", dbUser.getUsername());
        if (roleName != null) {
            response.put("roleName", roleName);
        }
        return response;
    }

    /** Change password — any authenticated user can change their own password */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestBody Map<String, String> body) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");

        if (currentPassword == null || newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid request. New password must be at least 8 characters.");
        }

        User user = repo.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (!encoder.matches(currentPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }

        user.setPassword(encoder.encode(newPassword));
        repo.save(user);
        return Map.of("message", "Password changed successfully");
    }

    /**
     * Admin reset-password — OWNER only.
     * Previously this endpoint had NO authorization check, meaning any
     * authenticated user could reset any other user's password.
     */
    @PostMapping("/reset-password")
    @PreAuthorize("hasRole('OWNER')")
    public String resetPassword(@RequestParam String username,
                                @RequestParam String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be at least 8 characters.");
        }
        User user = repo.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPassword(encoder.encode(newPassword));
        repo.save(user);
        return "Password reset successful";
    }
}