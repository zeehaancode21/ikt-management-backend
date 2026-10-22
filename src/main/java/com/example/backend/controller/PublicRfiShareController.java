package com.example.backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.dto.PublicRfiShareDto;
import com.example.backend.entity.ApiResponse;
import com.example.backend.service.PublicRfiShareService;

/**
 * PUBLIC, READ-ONLY endpoint for client RFI links. No login - the unguessable
 * token in the URL is the credential.
 *
 * The path sits under /public/share/, which is ALREADY whitelisted in
 * SecurityConfig (permitAll) and JwtFilter.shouldNotFilter - so nothing needs
 * to change there. ("/public/share/rfi/{token}" has two segments after /share,
 * so it can never clash with "/public/share/{token}".)
 *
 * Only GET exists; there is intentionally no write endpoint under /public.
 *
 * Errors come from PublicRfiShareService as ResponseStatusException:
 *   404 - unknown token / RFI or project gone
 *   410 - link revoked or expired
 *   429 - too many first-time lookups right now (load shedding)
 *
 * Efficiency: data comes from an in-memory cache and every reply carries an
 * ETag. The browser re-asks with If-None-Match and, when nothing changed, gets
 * an empty 304 (no DB, almost no bandwidth). "no-cache" (not "no-store") is what
 * allows that: the browser may keep a copy but must check before every use.
 */
@RestController
@RequestMapping("/public/share/rfi")
public class PublicRfiShareController {

    private final PublicRfiShareService service;

    public PublicRfiShareController(PublicRfiShareService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<PublicRfiShareDto>> get(
            @PathVariable("token") String token,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        PublicRfiShareService.Snapshot snapshot = service.get(token);
        CacheControl cacheControl = CacheControl.noCache().cachePrivate();

        if (etagMatches(ifNoneMatch, snapshot.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(cacheControl)
                    .eTag(snapshot.etag())
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .header("Referrer-Policy", "no-referrer")
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .eTag(snapshot.etag())
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.success(snapshot.dto()));
    }

    /**
     * True if the browser's If-None-Match already contains our ETag. Proxies (e.g.
     * when compressing) may turn it into a weak "W/..." tag and the header may
     * list several tags, so compare loosely.
     */
    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        if (ifNoneMatch.trim().equals("*")) {
            return true;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
