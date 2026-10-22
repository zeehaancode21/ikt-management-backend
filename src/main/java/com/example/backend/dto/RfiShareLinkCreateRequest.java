package com.example.backend.dto;

/**
 * Body of POST /api/rfi-share-links.
 *
 * @param scope         "PROJECT" (all external RFIs of a project) or "RFI" (one RFI)
 * @param projectId     PROJECT scope: the project's id
 * @param rfiId         RFI scope: the RFI's id
 * @param expiresInDays null = never expires, otherwise 1..3650
 */
public record RfiShareLinkCreateRequest(
        String scope,
        Long projectId,
        Long rfiId,
        Integer expiresInDays) {
}
