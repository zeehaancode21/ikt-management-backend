package com.example.backend.dto;

import java.time.Instant;

import com.example.backend.entity.ShareLink;

/**
 * A share link as shown to the logged-in owner/lead in the Share dialog.
 * (Never returned by the public endpoint.)
 */
public record ShareLinkDto(
        Long id,
        String token,
        String scope,
        String client,
        String year,
        Long projectId,
        boolean showDetails,
        boolean active,
        boolean expired,
        Instant expiresAt,
        String createdBy,
        Instant createdAt,
        Instant lastViewedAt) {

    public static ShareLinkDto from(ShareLink l) {
        boolean expired = l.getExpiresAt() != null && Instant.now().isAfter(l.getExpiresAt());
        return new ShareLinkDto(
                l.getId(),
                l.getToken(),
                l.getScope() == null ? null : l.getScope().name(),
                l.getClient(),
                l.getShareYear(),
                l.getProjectId(),
                l.isShowDetails(),
                l.isActive(),
                expired,
                l.getExpiresAt(),
                l.getCreatedBy(),
                l.getCreatedAt(),
                l.getLastViewedAt());
    }
}