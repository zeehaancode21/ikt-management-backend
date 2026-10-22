package com.example.backend.dto;

import java.time.Instant;

import com.example.backend.entity.RfiShareLink;

/**
 * An RFI share link as shown to the logged-in owner/lead in the Share dialog.
 * (Never returned by the public endpoint.)
 */
public record RfiShareLinkDto(
        Long id,
        String token,
        String scope,
        String client,
        String projectName,
        Long projectId,
        Long rfiId,
        boolean active,
        boolean expired,
        Instant expiresAt,
        String createdBy,
        Instant createdAt,
        Instant lastViewedAt) {

    public static RfiShareLinkDto from(RfiShareLink l) {
        boolean expired = l.getExpiresAt() != null && Instant.now().isAfter(l.getExpiresAt());
        return new RfiShareLinkDto(
                l.getId(),
                l.getToken(),
                l.getScope(),
                l.getClient(),
                l.getProjectName(),
                l.getProjectId(),
                l.getRfiId(),
                l.isActive(),
                expired,
                l.getExpiresAt(),
                l.getCreatedBy(),
                l.getCreatedAt(),
                l.getLastViewedAt());
    }
}
