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

    @PostMapping("/register")
    public User register(@RequestBody RegisterRequest request) {
        if (repo.findByUsername(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPassword(encoder.encode(request.password()));
        user.setRole(request.role() == null || request.role().isBlank() ? "USER" : request.role());

        // Never return the password hash in the response
        User saved = repo.save(user);
        saved.setPassword(null);

        // Optional custom role name (display title), e.g. "Senior Checker".
        if (request.roleName() != null && !request.roleName().isBlank()) {
            String trimmedRoleName = request.roleName().trim();
            if (trimmedRoleName.length() > ROLE_NAME_MAX_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Role name must be " + ROLE_NAME_MAX_LENGTH + " characters or fewer");
            }
            EmployeeProfile profile = employeeProfileRepository.findByUsername(request.username())
                    .orElseGet(EmployeeProfile::new);
            profile.setUsername(request.username());
            profile.setRoleName(trimmedRoleName);
            employeeProfileRepository.save(profile);
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

        // Custom display title (e.g. "Senior Checker") set by an admin via
        // Employee Management. The frontend falls back to displaying the
        // system role (below) when this is absent.
        String roleName = employeeProfileRepository.findByUsername(dbUser.getUsername())
                .map(EmployeeProfile::getRoleName)
                .filter(rn -> rn != null && !rn.isBlank())
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