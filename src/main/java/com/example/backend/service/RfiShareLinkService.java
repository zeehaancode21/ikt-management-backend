package com.example.backend.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.dto.RfiShareLinkCreateRequest;
import com.example.backend.dto.RfiShareLinkDto;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.entity.RfiShareLink;
import com.example.backend.entity.RfiTeamType;
import com.example.backend.repository.ProjectStatusRepository;
import com.example.backend.repository.RfiShareLinkRepository;
import com.example.backend.repository.RfiShareReadRepository;

/**
 * Creates / lists / revokes read-only RFI share links (owner / lead side).
 * The PUBLIC snapshot a link resolves to is built (and cached) by
 * {@link PublicRfiShareService}.
 *
 * Only EXTERNAL RFIs (the ones exchanged with the client) can be shared.
 * INTERNAL RFIs are between employees and never appear on a client page.
 *
 * Errors are thrown as ResponseStatusException so GlobalExceptionHandler keeps
 * the real HTTP status (400 / 404) instead of turning it into a generic 400.
 */
@Service
@Transactional
public class RfiShareLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 24 random bytes = 192 bits of entropy -> 32 URL-safe characters. */
    private static final int TOKEN_BYTES = 24;

    private static final int MAX_EXPIRY_DAYS = 3650;

    private final RfiShareLinkRepository linkRepository;
    private final ProjectStatusRepository projectStatusRepository;
    private final RfiShareReadRepository rfiReadRepository;
    private final PublicRfiShareService publicService;

    public RfiShareLinkService(RfiShareLinkRepository linkRepository,
                               ProjectStatusRepository projectStatusRepository,
                               RfiShareReadRepository rfiReadRepository,
                               PublicRfiShareService publicService) {
        this.linkRepository = linkRepository;
        this.projectStatusRepository = projectStatusRepository;
        this.rfiReadRepository = rfiReadRepository;
        this.publicService = publicService;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Management (called by logged-in owners / leads)
    // ═════════════════════════════════════════════════════════════════════

    public RfiShareLinkDto create(RfiShareLinkCreateRequest req, String createdBy) {
        if (req == null) {
            throw badRequest("Request body is required.");
        }

        String scope = parseScope(req.scope());
        RfiShareLink link = new RfiShareLink();
        link.setScope(scope);

        if (RfiShareLink.SCOPE_PROJECT.equals(scope)) {
            if (req.projectId() == null) {
                throw badRequest("projectId is required for a project link.");
            }
            ProjectStatus project = projectStatusRepository.findById(req.projectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
            link.setProjectId(project.getId());
            link.setProjectName(project.getProjectName());
            link.setClient(project.getClient());
        } else {
            if (req.rfiId() == null) {
                throw badRequest("rfiId is required for an RFI link.");
            }
            var rfi = rfiReadRepository.findRowById(req.rfiId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RFI not found."));
            if (rfi.getTeamType() != RfiTeamType.EXTERNAL) {
                throw badRequest("Only EXTERNAL RFIs can be shared with a client. "
                        + "Internal RFIs stay inside the team.");
            }
            link.setRfiId(rfi.getId());
            link.setProjectName(rfi.getProjectName());
            link.setClient(projectStatusRepository.findByProjectName(rfi.getProjectName())
                    .map(ProjectStatus::getClient).orElse(null));
        }

        Integer days = req.expiresInDays();
        if (days != null) {
            if (days < 1 || days > MAX_EXPIRY_DAYS) {
                throw badRequest("expiresInDays must be between 1 and " + MAX_EXPIRY_DAYS + ".");
            }
            link.setExpiresAt(Instant.now().plus(days, ChronoUnit.DAYS));
        }

        link.setActive(true);
        link.setToken(newUniqueToken());
        link.setCreatedBy(createdBy);
        link.setCreatedAt(Instant.now());

        return RfiShareLinkDto.from(linkRepository.save(link));
    }

    @Transactional(readOnly = true)
    public List<RfiShareLinkDto> list(String scopeRaw, Long projectId, Long rfiId) {
        String scope = parseScope(scopeRaw);
        List<RfiShareLink> links;

        if (RfiShareLink.SCOPE_PROJECT.equals(scope)) {
            if (projectId == null) {
                throw badRequest("projectId is required for a project link.");
            }
            links = linkRepository.findByScopeAndProjectIdAndActiveTrueOrderByCreatedAtDesc(scope, projectId);
        } else {
            if (rfiId == null) {
                throw badRequest("rfiId is required for an RFI link.");
            }
            links = linkRepository.findByScopeAndRfiIdAndActiveTrueOrderByCreatedAtDesc(scope, rfiId);
        }
        return links.stream().map(RfiShareLinkDto::from).toList();
    }

    /** Revokes (deactivates) a link. Idempotent. The row is kept for audit. */
    public void revoke(Long id) {
        RfiShareLink link = linkRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found."));
        if (link.isActive()) {
            link.setActive(false);
            linkRepository.save(link);
        }
        // Drop the cached public copy once the revoke is committed, so the link
        // stops working immediately instead of when the cache entry expires.
        publicService.evictAfterCommit(link.getToken());
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    private String newUniqueToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!linkRepository.existsByToken(token)) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique share token.");
    }

    private String parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("scope is required (PROJECT or RFI).");
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!RfiShareLink.SCOPE_PROJECT.equals(s) && !RfiShareLink.SCOPE_RFI.equals(s)) {
            throw badRequest("scope must be PROJECT or RFI.");
        }
        return s;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
