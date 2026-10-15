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

import com.example.backend.dto.ShareLinkCreateRequest;
import com.example.backend.dto.ShareLinkDto;
import com.example.backend.entity.ApiResponse;
import com.example.backend.service.ShareLinkService;

/**
 * Management API for share links. Requires a logged-in OWNER or LEAD (the same
 * roles that can open the Projects page). The PUBLIC, token-only endpoint lives
 * in {@link PublicShareController}.
 *
 * The role check is done manually (instead of @PreAuthorize) so a non-owner
 * gets a proper 403: GlobalExceptionHandler would otherwise turn Spring's
 * AccessDeniedException into a 500.
 */
@RestController
@RequestMapping("/share-links")
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    public ShareLinkController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    /** Create a new link. */
    @PostMapping
    public ResponseEntity<ApiResponse<ShareLinkDto>> create(
            @RequestBody ShareLinkCreateRequest request,
            Authentication authentication) {
        requireOwnerOrLead(authentication);
        ShareLinkDto created = shareLinkService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Share link created", created));
    }

    /**
     * List active links for a target.
     *   GET /share-links?scope=CLIENT&client=Acme
     *   GET /share-links?scope=PROJECT&projectId=42
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ShareLinkDto>>> list(
            @RequestParam("scope") String scope,
            @RequestParam(value = "client", required = false) String client,
            @RequestParam(value = "projectId", required = false) Long projectId,
            Authentication authentication) {
        requireOwnerOrLead(authentication);
        return ResponseEntity.ok(ApiResponse.success(shareLinkService.list(scope, client, projectId)));
    }

    /** Revoke a link immediately (anyone holding it gets "revoked" on their next refresh). */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(
            @PathVariable("id") Long id,
            Authentication authentication) {
        requireOwnerOrLead(authentication);
        shareLinkService.revoke(id);
        return ResponseEntity.ok(ApiResponse.deleteSuccess(id));
    }

    private void requireOwnerOrLead(Authentication authentication) {
        boolean allowed = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()) || "ROLE_LEAD".equals(a.getAuthority()));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only owners and leads can manage share links.");
        }
    }
}