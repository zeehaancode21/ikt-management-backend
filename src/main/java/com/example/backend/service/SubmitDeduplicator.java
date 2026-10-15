package com.example.backend.service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Makes "send" endpoints idempotent against double submits.
 *
 * Problem: a double click, or a client / proxy retry while Gmail is slow, hits the
 * endpoint twice with the exact same payload. Each request ran the whole flow, so the
 * same RFI / email went out twice.
 *
 * Fix: the FIRST request with a given fingerprint runs the action. Any identical request
 * that arrives while it is running (or within a few seconds after it finished) does NOT
 * send again - it waits for the first one and gets the very same result back, so the UI
 * still sees a normal success response. If the first attempt fails, the entry is dropped
 * so the user can retry normally.
 *
 * Note: state is in memory, so this protects a single backend instance. If you ever run
 * several instances behind a load balancer, move this to a shared store (e.g. Redis).
 */
@Component
public class SubmitDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SubmitDeduplicator.class);

    /** How long a finished (successful) send keeps absorbing identical repeats. */
    private static final long COMPLETED_TTL_MS = TimeUnit.SECONDS.toMillis(30);
    /** Safety net so a stuck in-flight entry can never block a user forever. */
    private static final long IN_FLIGHT_MAX_MS = TimeUnit.MINUTES.toMillis(3);

    private static final class Entry {
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final long startedAt = System.currentTimeMillis();
        volatile long completedAt = 0L;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T runOnce(String key, Callable<T> action) throws Exception {
        purgeExpired();

        Entry mine = new Entry();
        Entry existing = entries.putIfAbsent(key, mine);

        if (existing != null) {
            log.warn("Duplicate submit ignored (same payload already sent/in progress): {}", key);
            try {
                return (T) existing.future.get(IN_FLIGHT_MAX_MS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw e;
            } catch (TimeoutException e) {
                throw new IllegalStateException("The original request is still being processed", e);
            }
        }

        try {
            T result = action.call();
            mine.completedAt = System.currentTimeMillis();
            mine.future.complete(result);
            return result;
        } catch (Exception e) {
            // Failed -> let the user retry; waiting duplicates get the same error.
            entries.remove(key, mine);
            mine.future.completeExceptionally(e);
            throw e;
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            boolean doneAndOld = e.completedAt > 0 && now - e.completedAt > COMPLETED_TTL_MS;
            boolean stuck = e.completedAt == 0 && now - e.startedAt > IN_FLIGHT_MAX_MS;
            if (doneAndOld || stuck) {
                it.remove();
            }
        }
    }

    // ── Fingerprint helpers ─────────────────────────────────────────────

    /** Builds a stable key from the request parts (null-safe). */
    public static String key(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "<null>" : p.toString()).append('|');
        }
        return sb.toString();
    }

    /** SHA-256 of a file's bytes (name + size included) so identical re-sends match. */
    public static String fileFingerprint(MultipartFile f) {
        if (f == null || f.isEmpty()) {
            return "nofile";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Stream in 8 KB chunks: f.getBytes() copied the whole file (up to 50 MB) into the heap just to hash it.
            try (java.io.InputStream in = f.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            String hash = HexFormat.of().formatHex(md.digest());
            return f.getOriginalFilename() + ":" + f.getSize() + ":" + hash;
        } catch (Exception e) {
            return f.getOriginalFilename() + ":" + f.getSize();
        }
    }

    public static String filesFingerprint(java.util.List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return "nofiles";
        }
        StringBuilder sb = new StringBuilder();
        for (MultipartFile f : files) {
            sb.append(fileFingerprint(f)).append(',');
        }
        return sb.toString();
    }
}