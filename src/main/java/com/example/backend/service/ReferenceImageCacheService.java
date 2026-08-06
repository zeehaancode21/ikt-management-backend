// ReferenceImageCacheService.java
package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our chosen free image-generation provider (Pollinations.ai's "kontext"
 * model) does image-to-image / style-reference generation by fetching a
 * reference image from a URL we give it — it doesn't accept an uploaded
 * file directly. Since users attach their reference image to us as a
 * base64 blob (not a public URL), we briefly re-host it ourselves at a
 * random, unguessable, short-lived URL so the provider can fetch it.
 *
 * This is intentionally an in-memory cache, not persisted storage: entries
 * exist only long enough for the AI provider to retrieve them (a few
 * minutes) and are never meant to be reachable after that.
 */
@Slf4j
@Service
public class ReferenceImageCacheService {

    private static final long TTL_MILLIS = 10 * 60 * 1000L; // 10 minutes
    private static final long MAX_BYTES = 5 * 1024 * 1024L; // 5MB, matches frontend upload limit

    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    private final Map<String, CachedRef> cache = new ConcurrentHashMap<>();

    private record CachedRef(byte[] bytes, String contentType, long expiresAtEpochMs) {
    }

    /**
     * Decodes a base64 (optionally data-URL prefixed) image, caches it, and
     * returns a public URL the AI provider can GET it from.
     */
    public String cacheAndGetPublicUrl(String base64OrDataUrl) throws Exception {
        if (base64OrDataUrl == null || base64OrDataUrl.isBlank()) {
            throw new IllegalArgumentException("Reference image is empty");
        }

        String contentType = "image/png";
        String rawBase64 = base64OrDataUrl;
        if (base64OrDataUrl.startsWith("data:")) {
            int commaIdx = base64OrDataUrl.indexOf(',');
            if (commaIdx == -1) {
                throw new IllegalArgumentException("Malformed reference image data URL");
            }
            String header = base64OrDataUrl.substring(5, commaIdx); // e.g. "image/png;base64"
            int semi = header.indexOf(';');
            if (semi != -1) {
                contentType = header.substring(0, semi);
            }
            rawBase64 = base64OrDataUrl.substring(commaIdx + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(rawBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Reference image could not be decoded: " + e.getMessage());
        }

        if (bytes.length == 0) {
            throw new IllegalArgumentException("Reference image is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Reference image must be smaller than 5MB");
        }

        String token = UUID.randomUUID().toString();
        cache.put(token, new CachedRef(bytes, contentType, Instant.now().toEpochMilli() + TTL_MILLIS));

        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + "/social-post/ai-image/reference-source/" + token;
    }

    /**
     * Looks up a cached reference image by token. Returns null if missing or
     * expired (a 404 is returned to the caller in that case).
     */
    public byte[] getBytes(String token) {
        CachedRef ref = getIfPresent(token);
        return ref == null ? null : ref.bytes();
    }

    public String getContentType(String token) {
        CachedRef ref = getIfPresent(token);
        return ref == null ? null : ref.contentType();
    }

    private CachedRef getIfPresent(String token) {
        CachedRef ref = cache.get(token);
        if (ref == null) {
            return null;
        }
        if (ref.expiresAtEpochMs() < Instant.now().toEpochMilli()) {
            cache.remove(token);
            return null;
        }
        return ref;
    }

    /** Periodically sweeps expired entries so the cache doesn't grow unbounded. */
    @Scheduled(fixedRate = 5 * 60 * 1000L)
    public void purgeExpired() {
        long now = Instant.now().toEpochMilli();
        int before = cache.size();
        cache.entrySet().removeIf(e -> e.getValue().expiresAtEpochMs() < now);
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("Purged {} expired reference image cache entries", removed);
        }
    }
}