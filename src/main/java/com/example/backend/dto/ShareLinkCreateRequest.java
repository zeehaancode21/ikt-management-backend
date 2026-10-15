package com.example.backend.dto;

/**
 * Body of POST /share-links.
 *
 * @param scope         "CLIENT" or "PROJECT"
 * @param client        CLIENT scope: the client name
 * @param year          CLIENT scope: a single year, or null/blank for all years
 * @param projectId     PROJECT scope: the project's id
 * @param expiresInDays null = never expires, otherwise 1..3650
 * @param showDetails   true = also expose team names and remarks (default false)
 */
public record ShareLinkCreateRequest(
        String scope,
        String client,
        String year,
        Long projectId,
        Integer expiresInDays,
        Boolean showDetails) {
}