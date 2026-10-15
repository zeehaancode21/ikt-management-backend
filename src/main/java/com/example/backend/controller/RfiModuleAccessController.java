package com.example.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.repository.UserRepository;
import com.example.backend.security.RfiModuleAccessInterceptor;

/**
 * Who may use the RFI module.
 *
 *   GET /api/rfi-access/me      any logged-in user -> { "allowed": true|false }   (the app uses this
 *                               to show/hide the RFI menu)
 *   GET /api/rfi-access         OWNER only -> [userId, ...] of everyone switched on
 *   PUT /api/rfi-access/{id}    OWNER only, body { "allowed": true|false }
 *
 * Stored as one boolean column (users.rfi_access). Nothing else is kept.
 */
@RestController
@RequestMapping("/api/rfi-access")
public class RfiModuleAccessController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RfiModuleAccessInterceptor moduleAccess;

    @GetMapping("/me")
    public Map<String, Boolean> me() {
        return Map.of("allowed", moduleAccess.canUse(SecurityContextHolder.getContext().getAuthentication()));
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public List<Long> listGranted() {
        return userRepository.findRfiAccessUserIds();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> set(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Boolean allowed = body == null ? null : body.get("allowed");
        if (allowed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send { \"allowed\": true } or { \"allowed\": false }");
        }
        if (userRepository.setRfiAccess(id, allowed) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
        }
        return Map.of("id", id, "allowed", allowed);
    }
}