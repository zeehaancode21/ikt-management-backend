package com.example.backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.dto.RfiShareLinkCreateRequest;
import com.example.backend.dto.RfiShareLinkDto;
import com.example.backend.entity.ApiResponse;
import com.example.backend.service.RfiShareLinkService;

/**
 * Management API for RFI share links. Requires a logged-in OWNER (nobody else can
 * create, list or revoke links) and, via WebConfig, access to the RFI module.
 * The PUBLIC, token-only endpoint lives in {@link PublicRfiShareController}.
 *
 * The role check is done manually (instead of @PreAuthorize) so a non-owner gets
 * a proper 403: GlobalExceptionHandler would otherwise turn Spring's
 * AccessDeniedException into a 500.
 */
@RestController
@RequestMapping("/api/rfi-share-links")
public class RfiShareLinkController {

    private final RfiShareLinkService service;

    public RfiShareLinkController(RfiShareLinkService service) {
        this.service = service;
    }

    /** Create a new link. */
    @PostMapping
    public ResponseEntity<ApiResponse<RfiShareLinkDto>> create(
            @RequestBody RfiShareLinkCreateRequest request,
            Authentication authentication) {
        requireOwner(authentication);
        RfiShareLinkDto created = service.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Share link created", created));
    }

    /**
     * List active links for a target.
     *   GET /api/rfi-share-links?scope=PROJECT&projectId=42
     *   GET /api/rfi-share-links?scope=RFI&rfiId=17
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RfiShareLinkDto>>> list(
            @RequestParam("scope") String scope,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "rfiId", required = false) Long rfiId,
            Authentication authentication) {
        requireOwner(authentication);
        return ResponseEntity.ok(ApiResponse.success(service.list(scope, projectId, rfiId)));
    }

    /** Revoke a link immediately (anyone holding it gets "revoked" on their next refresh). */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(
            @PathVariable("id") Long id,
            Authentication authentication) {
        requireOwner(authentication);
        service.revoke(id);
        return ResponseEntity.ok(ApiResponse.deleteSuccess(id));
    }

    private void requireOwner(Authentication authentication) {
        boolean allowed = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the owner can manage RFI share links.");
        }
    }
}