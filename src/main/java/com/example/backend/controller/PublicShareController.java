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

import com.example.backend.dto.PublicShareDto;
import com.example.backend.entity.ApiResponse;
import com.example.backend.service.PublicShareService;

/**
 * PUBLIC, READ-ONLY endpoint for client share links. No login required - the
 * unguessable token in the URL is the credential.
 *
 * This is whitelisted in BOTH SecurityConfig (permitAll) and
 * JwtFilter.shouldNotFilter. It only exposes GET; there is intentionally no
 * write endpoint under /public.
 *
 * Errors come from PublicShareService as ResponseStatusException:
 *   404 - unknown token / project deleted
 *   410 - link revoked or expired
 *
 * Efficiency: the data comes from a short in-memory cache (see
 * PublicShareService) and every reply carries an ETag. The browser re-asks
 * with If-None-Match and, when nothing changed, gets an empty 304 instead of
 * the whole payload. "no-cache" (not "no-store") is what allows that: the
 * browser may keep a copy but must check with us before every use, so the
 * client never sees stale data.
 */
@RestController
@RequestMapping("/public/share")
public class PublicShareController {

    private final PublicShareService publicShareService;

    public PublicShareController(PublicShareService publicShareService) {
        this.publicShareService = publicShareService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<PublicShareDto>> get(
            @PathVariable("token") String token,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        PublicShareService.Snapshot snapshot = publicShareService.get(token);
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
     * True if the browser's If-None-Match already contains our ETag.
     * Proxies (e.g. when compressing) may turn it into a weak "W/..." tag, and
     * the header may list several tags, so compare loosely.
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