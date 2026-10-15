package com.example.backend.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.dto.ShareLinkCreateRequest;
import com.example.backend.dto.ShareLinkDto;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.entity.ShareLink;
import com.example.backend.entity.ShareScope;
import com.example.backend.repository.ProjectStatusRepository;
import com.example.backend.repository.ShareLinkRepository;

/**
 * Creates / lists / revokes read-only share links (owner / lead side).
 * The PUBLIC snapshot a link resolves to is built (and cached) by
 * {@link PublicShareService}.
 *
 * Errors are thrown as ResponseStatusException so GlobalExceptionHandler keeps
 * the real HTTP status (404 / 410 / 400 / 403) instead of turning it into a 400.
 */
@Service
@Transactional
public class ShareLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 24 random bytes = 192 bits of entropy -> 32 URL-safe characters. */
    private static final int TOKEN_BYTES = 24;

    private static final int MAX_EXPIRY_DAYS = 3650;

    private final ShareLinkRepository shareLinkRepository;
    private final ProjectStatusRepository projectStatusRepository;
    private final PublicShareService publicShareService;

    public ShareLinkService(ShareLinkRepository shareLinkRepository,
                            ProjectStatusRepository projectStatusRepository,
                            PublicShareService publicShareService) {
        this.shareLinkRepository = shareLinkRepository;
        this.projectStatusRepository = projectStatusRepository;
        this.publicShareService = publicShareService;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Management (called by logged-in owners / leads)
    // ═════════════════════════════════════════════════════════════════════

    public ShareLinkDto create(ShareLinkCreateRequest req, String createdBy) {
        if (req == null) {
            throw badRequest("Request body is required.");
        }

        ShareScope scope = parseScope(req.scope());
        ShareLink link = new ShareLink();
        link.setScope(scope);

        if (scope == ShareScope.PROJECT) {
            if (req.projectId() == null) {
                throw badRequest("projectId is required for a project link.");
            }
            ProjectStatus project = projectStatusRepository.findById(req.projectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
            link.setProjectId(project.getId());
            link.setClient(project.getClient());
            link.setShareYear(project.getYear());
        } else {
            String client = blankToNull(req.client());
            if (client == null) {
                throw badRequest("client is required for a client link.");
            }
            String year = blankToNull(req.year());

            List<ProjectStatus> matches = projectsForClient(client, year);
            if (matches.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No projects found for that client" + (year != null ? " in " + year : "") + ".");
            }
            link.setClient(matches.get(0).getClient()); // canonical spelling from the DB
            link.setShareYear(year);
        }

        Integer days = req.expiresInDays();
        if (days != null) {
            if (days < 1 || days > MAX_EXPIRY_DAYS) {
                throw badRequest("expiresInDays must be between 1 and " + MAX_EXPIRY_DAYS + ".");
            }
            link.setExpiresAt(Instant.now().plus(days, ChronoUnit.DAYS));
            // TESTING ONLY: to test expiry quickly, swap DAYS for MINUTES on the line above,
            // then switch it back to DAYS before going live.
        }

        link.setShowDetails(Boolean.TRUE.equals(req.showDetails()));
        link.setActive(true);
        link.setToken(newUniqueToken());
        link.setCreatedBy(createdBy);
        link.setCreatedAt(Instant.now());

        return ShareLinkDto.from(shareLinkRepository.save(link));
    }

    @Transactional(readOnly = true)
    public List<ShareLinkDto> list(String scopeRaw, String client, Long projectId) {
        ShareScope scope = parseScope(scopeRaw);
        List<ShareLink> links;

        if (scope == ShareScope.PROJECT) {
            if (projectId == null) {
                throw badRequest("projectId is required for a project link.");
            }
            links = shareLinkRepository
                    .findByScopeAndProjectIdAndActiveTrueOrderByCreatedAtDesc(scope, projectId);
        } else {
            String c = blankToNull(client);
            if (c == null) {
                throw badRequest("client is required for a client link.");
            }
            links = shareLinkRepository
                    .findByScopeAndClientAndActiveTrueOrderByCreatedAtDesc(scope, c);
        }

        return links.stream().map(ShareLinkDto::from).toList();
    }

    /** Revokes (deactivates) a link. Idempotent. The row is kept for audit. */
    public void revoke(Long id) {
        ShareLink link = shareLinkRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found."));
        if (link.isActive()) {
            link.setActive(false);
            shareLinkRepository.save(link);
        }
        // Drop the cached public copy as soon as the revoke is committed, so
        // the link stops working immediately instead of after the cache expires.
        publicShareService.evictAfterCommit(link.getToken());
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Projects of a client, optionally limited to one year. The DB comparison
     * can be case-insensitive (MySQL default collation) whereas the Projects
     * page groups clients with exact string equality, so filter again here to
     * exactly match what the owner saw when creating the link.
     */
    private List<ProjectStatus> projectsForClient(String client, String year) {
        return projectStatusRepository.findByClient(client).stream()
                .filter(p -> client.equals(p.getClient()))
                .filter(p -> year == null || year.equals(p.getYear()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String newUniqueToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!shareLinkRepository.existsByToken(token)) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique share token.");
    }

    private ShareScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("scope is required (CLIENT or PROJECT).");
        }
        try {
            return ShareScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("scope must be CLIENT or PROJECT.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}