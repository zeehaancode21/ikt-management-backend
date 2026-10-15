package com.example.backend.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.repository.UserRepository;

/**
 * Who may use the RFI module and the Projects (Dashboard) module.
 *
 *   GET /api/module-access/me   any logged-in user -> { "rfi": true|false, "projects": true|false }
 *                               (the app uses this to show/hide menu items and guard pages)
 *   GET /api/module-access      OWNER only -> [{ id, rfi, projects }] for everyone with at least one switch on
 *   PUT /api/module-access/{id} OWNER only, body { "rfi": true } and/or { "projects": false }
 *                               (send only the field you want to change)
 *
 * Stored as two boolean columns on the existing users row (rfi_access, projects_access).
 * Nothing else is kept.
 *
 * The old /api/rfi-access endpoints (RfiModuleAccessController) still work, so a browser tab that
 * still has the old frontend loaded keeps working during deployment. You can delete that
 * controller once the new frontend is live.
 */
@RestController
@RequestMapping("/api/module-access")
public class ModuleAccessController {

    @Autowired
    private UserRepository userRepository;

    public record Grant(Long id, boolean rfi, boolean projects) {}

    @GetMapping("/me")
    public Map<String, Boolean> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Map.of("rfi", false, "projects", false);
        }
        boolean owner = auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        if (owner) {
            return Map.of("rfi", true, "projects", true);
        }
        return userRepository.findModuleAccessByUsername(auth.getName())
                .map(row -> Map.of(
                        "rfi", Boolean.TRUE.equals(row.getRfi()),
                        "projects", Boolean.TRUE.equals(row.getProjects())))
                .orElse(Map.of("rfi", false, "projects", false));
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public List<Grant> listGrants() {
        return userRepository.findModuleGrants().stream()
                .map(r -> new Grant(r.getId(), Boolean.TRUE.equals(r.getRfi()), Boolean.TRUE.equals(r.getProjects())))
                .toList();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> set(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Boolean rfi = body == null ? null : body.get("rfi");
        Boolean projects = body == null ? null : body.get("projects");
        if (rfi == null && projects == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send { \"rfi\": true|false } and/or { \"projects\": true|false }");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        if (rfi != null) {
            if (userRepository.setRfiAccess(id, rfi) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
            }
            result.put("rfi", rfi);
        }
        if (projects != null) {
            if (userRepository.setProjectsAccess(id, projects) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
            }
            result.put("projects", projects);
        }
        return result;
    }
}