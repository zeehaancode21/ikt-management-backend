package com.example.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.dto.PublicRfiShareDto;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.entity.RfiShareLink;
import com.example.backend.entity.RfiTeamType;
import com.example.backend.repository.ProjectStatusRepository;
import com.example.backend.repository.RfiShareHistoryReadRepository;
import com.example.backend.repository.RfiShareLinkRepository;
import com.example.backend.repository.RfiShareReadRepository;

/**
 * Builds the read-only snapshot behind GET /public/share/rfi/{token}, with an
 * in-memory cache so the Railway database and the Render instance stay quiet.
 * (Same idea as {@link PublicShareService}, plus push-style invalidation.)
 *
 * COST MODEL - what actually touches the database
 * ------------------------------------------------
 *  - Nothing runs in the background. No scheduler, no websocket, no SSE, no
 *    Gmail polling. If nobody has a link open, this class costs exactly zero.
 *  - A link that IS open costs at most ONE reload (3 small column-limited
 *    queries) per FRESH_SECONDS, no matter how many people have it open
 *    (concurrent requests share a single load).
 *  - "Live" comes from INVALIDATION, not from short polling: whenever an RFI is
 *    created, changes status (reply / resubmit / reopen / email import / manual)
 *    or changes priority, the app calls {@link #evictProjectAfterCommit}. The
 *    cached copy for that project is then marked stale, so the very next poll
 *    reloads it. That is why FRESH_SECONDS can be long (3 minutes) while a change
 *    still reaches the client within one poll. The long TTL is only a safety net
 *    for changes that bypass the hooks (e.g. a project rename).
 *  - The browser polls with If-None-Match. When nothing changed the reply is an
 *    empty 304 served from memory: no DB, almost no bandwidth.
 *  - "last viewed" is written at most once per TOUCH_INTERVAL_SECONDS per link
 *    using a tiny UPDATE (no entity load).
 *  - Unknown / revoked / expired tokens are remembered for a minute, so scanners
 *    and stale tabs can't hammer the DB.
 *  - Load shedding: a token we have never loaded can only cost a DB lookup
 *    COLD_LOADS_PER_MINUTE times a minute in total. Past that we answer 429
 *    without touching the DB, so a flood of random tokens cannot spike Railway.
 *  - If the DB is briefly unreachable, the last good snapshot is served (up to
 *    STALE_OK_MINUTES) instead of an error.
 *
 * MEMORY: at most MAX_ENTRIES snapshots, each capped at MAX_RFIS RFIs with at
 * most MAX_HISTORY_PER_RFI history rows each (a few tens of KB).
 *
 * Assumes ONE backend instance (Render). With several instances each keeps its
 * own cache and an invalidation only reaches the instance that handled it
 * (others catch up within FRESH_SECONDS).
 *
 * Deliberately NOT @Transactional: a cache hit must not open a DB transaction.
 * Only the actual load runs in a short read-only transaction (TransactionTemplate).
 *
 * Tunable without a code change (optional env / properties):
 *   app.rfishare.fresh-seconds        default 180
 *   app.rfishare.max-rfis             default 200
 *   app.rfishare.cold-loads-per-min   default 40
 */
@Service
public class PublicRfiShareService {

    private static final Logger log = LoggerFactory.getLogger(PublicRfiShareService.class);

    // ── Fixed tuning ──────────────────────────────────────────────────────
    private static final long MISS_SECONDS = 60;
    private static final long TOUCH_INTERVAL_SECONDS = 300;
    private static final long STALE_OK_MINUTES = 10;
    private static final long RETRY_AFTER_FAILURE_SECONDS = 10;
    private static final long COLD_WINDOW_SECONDS = 60;

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_MISSES = 2000;
    private static final int MAX_HISTORY_PER_RFI = 40;
    private static final int MAX_HISTORY_ROWS = 3000;
    private static final int LOCK_STRIPES = 32;

    /** Real tokens are 32 URL-safe chars; reject junk without touching the DB. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private static final long MISS_NANOS = TimeUnit.SECONDS.toNanos(MISS_SECONDS);
    private static final long STALE_OK_NANOS = TimeUnit.MINUTES.toNanos(STALE_OK_MINUTES);
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(RETRY_AFTER_FAILURE_SECONDS);
    private static final long COLD_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(COLD_WINDOW_SECONDS);

    // ── Public result type ────────────────────────────────────────────────
    /** A snapshot plus its ETag (a fingerprint of the data, not of the time). */
    public record Snapshot(PublicRfiShareDto dto, String etag) {
    }

    // ── Cache internals ───────────────────────────────────────────────────
    private record LinkInfo(Long id, Instant expiresAt) {
    }

    private static final class Entry {
        final LinkInfo link;
        final Snapshot snapshot;
        /** Lower-cased project name this snapshot belongs to (for invalidation). */
        final String projectKey;
        final long loadedNanos;
        volatile long freshUntil;
        volatile Instant lastTouch;

        Entry(LinkInfo link, Snapshot snapshot, String projectKey, long loadedNanos,
              long freshNanos, Instant lastTouch) {
            this.link = link;
            this.snapshot = snapshot;
            this.projectKey = projectKey;
            this.loadedNanos = loadedNanos;
            this.freshUntil = loadedNanos + freshNanos;
            this.lastTouch = lastTouch;
        }
    }

    private static final class Miss {
        final HttpStatus status;
        final String message;
        final long expiresNanos;

        Miss(HttpStatus status, String message, long expiresNanos) {
            this.status = status;
            this.message = message;
            this.expiresNanos = expiresNanos;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Miss> misses = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[LOCK_STRIPES];

    // Fixed-window counter for loads of tokens we hold no cached copy of.
    private final AtomicLong coldWindowStart = new AtomicLong(System.nanoTime());
    private final AtomicInteger coldWindowCount = new AtomicInteger();

    private final long freshNanos;
    private final int maxRfis;
    private final int coldLoadsPerMinute;

    private final RfiShareLinkRepository linkRepository;
    private final ProjectStatusRepository projectStatusRepository;
    private final RfiShareReadRepository rfiReadRepository;
    private final RfiShareHistoryReadRepository historyReadRepository;
    private final TransactionTemplate readTx;

    public PublicRfiShareService(
            RfiShareLinkRepository linkRepository,
            ProjectStatusRepository projectStatusRepository,
            RfiShareReadRepository rfiReadRepository,
            RfiShareHistoryReadRepository historyReadRepository,
            PlatformTransactionManager transactionManager,
            @Value("${app.rfishare.fresh-seconds:180}") long freshSeconds,
            @Value("${app.rfishare.max-rfis:200}") int maxRfis,
            @Value("${app.rfishare.cold-loads-per-min:40}") int coldLoadsPerMinute) {
        this.linkRepository = linkRepository;
        this.projectStatusRepository = projectStatusRepository;
        this.rfiReadRepository = rfiReadRepository;
        this.historyReadRepository = historyReadRepository;
        this.freshNanos = TimeUnit.SECONDS.toNanos(Math.max(10, freshSeconds));
        this.maxRfis = Math.max(1, Math.min(maxRfis, 500));
        this.coldLoadsPerMinute = Math.max(5, coldLoadsPerMinute);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Returns the current snapshot for a token.
     * Throws ResponseStatusException 404 (unknown token / RFI or project gone),
     * 410 (revoked / expired) or 429 (too many first-time lookups right now).
     */
    public Snapshot get(String token) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is not valid.");
        }

        long now = System.nanoTime();

        Miss miss = misses.get(token);
        if (miss != null) {
            if (miss.expiresNanos - now > 0) {
                throw new ResponseStatusException(miss.status, miss.message);
            }
            misses.remove(token, miss);
        }

        Entry hit = entries.get(token);
        if (hit != null && hit.freshUntil - now > 0) {
            return serve(token, hit);
        }

        // Cache miss / stale: only ONE thread per token goes to the DB.
        synchronized (lockFor(token)) {
            Entry current = entries.get(token);
            if (current != null && current.freshUntil - System.nanoTime() > 0) {
                return serve(token, current);
            }
            return serve(token, reload(token, current));
        }
    }

    /** Forget a token's cached snapshot (used when a link is revoked). */
    public void evict(String token) {
        if (token != null) {
            entries.remove(token);
        }
    }

    /**
     * Evict once the surrounding transaction (e.g. the revoke) has committed,
     * so a concurrent reload can't put the still-active copy back.
     */
    public void evictAfterCommit(String token) {
        runAfterCommit(() -> evict(token));
    }

    /**
     * Marks every cached snapshot that shows this project's RFIs as stale, so the
     * next poll reloads it. The old copy is kept (not removed) so it can still be
     * served if the database happens to be unreachable at that moment.
     *
     * Cheap and safe to call from anywhere: it only touches memory, never throws.
     */
    public void invalidateProject(String projectName) {
        try {
            String key = keyOf(projectName);
            if (key.isEmpty() || entries.isEmpty()) {
                return;
            }
            long expired = System.nanoTime() - 1;
            for (Entry e : entries.values()) {
                if (key.equals(e.projectKey)) {
                    e.freshUntil = expired;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("RFI share invalidation failed (ignored): {}", ex.toString());
        }
    }

    /**
     * Invalidate once the surrounding transaction has committed - so the reload
     * triggered by the next poll sees the NEW data, not the old. Runs right away
     * when there is no transaction. This is the method the RFI services call.
     */
    public void evictProjectAfterCommit(String projectName) {
        runAfterCommit(() -> invalidateProject(projectName));
    }

    private static void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Serving from cache
    // ═════════════════════════════════════════════════════════════════════

    private Snapshot serve(String token, Entry e) {
        Instant expiresAt = e.link.expiresAt();
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            entries.remove(token, e);
            rememberMiss(token, HttpStatus.GONE, "This link has expired.");
            throw new ResponseStatusException(HttpStatus.GONE, "This link has expired.");
        }
        touchLastViewed(e);
        return e.snapshot;
    }

    private void touchLastViewed(Entry e) {
        Instant now = Instant.now();
        synchronized (e) {
            Instant last = e.lastTouch;
            if (last != null && Duration.between(last, now).getSeconds() < TOUCH_INTERVAL_SECONDS) {
                return;
            }
            e.lastTouch = now;
        }
        try {
            linkRepository.touchLastViewed(e.link.id(), now);
        } catch (RuntimeException ex) {
            // Never fail a client's page just because the "last viewed" note couldn't be saved.
            log.warn("Could not update last_viewed_at for RFI share link {}: {}", e.link.id(), ex.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Loading from the database
    // ═════════════════════════════════════════════════════════════════════

    private Entry reload(String token, Entry previous) {
        // A token we hold nothing for is the only way to cause "new" DB work from
        // outside, so it is the one thing we rate-limit (see class comment).
        if (previous == null && !allowColdLoad()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests right now. Please try again in a minute.");
        }
        try {
            Instant carriedTouch = previous == null ? null : previous.lastTouch;
            Entry fresh = readTx.execute(status -> load(token, carriedTouch));
            entries.put(token, fresh);
            trimIfNeeded();
            return fresh;
        } catch (ResponseStatusException e) {
            entries.remove(token);
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND || status == HttpStatus.GONE) {
                rememberMiss(token, status, e.getReason());
            }
            throw e;
        } catch (RuntimeException e) {
            // Database hiccup (e.g. Railway waking up): keep showing the last good data.
            if (previous != null && System.nanoTime() - previous.loadedNanos < STALE_OK_NANOS) {
                log.warn("RFI share reload failed, serving previous copy: {}", e.toString());
                previous.freshUntil = System.nanoTime() + RETRY_NANOS;
                return previous;
            }
            throw e;
        }
    }

    private boolean allowColdLoad() {
        long now = System.nanoTime();
        long start = coldWindowStart.get();
        if (now - start > COLD_WINDOW_NANOS && coldWindowStart.compareAndSet(start, now)) {
            coldWindowCount.set(0);
        }
        return coldWindowCount.incrementAndGet() <= coldLoadsPerMinute;
    }

    /** Runs inside a short read-only transaction: 1 link query + 1 RFI query + 1 history query. */
    private Entry load(String token, Instant carriedTouch) {
        RfiShareLink link = linkRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is not valid."));

        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been revoked.");
        }
        if (link.getExpiresAt() != null && Instant.now().isAfter(link.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has expired.");
        }

        List<RfiShareReadRepository.RfiRow> rows;
        String projectName;
        String client;
        boolean truncated = false;
        boolean projectScope = RfiShareLink.SCOPE_PROJECT.equals(link.getScope());

        if (projectScope) {
            ProjectStatus project = projectStatusRepository.findById(link.getProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "This project is no longer available."));
            projectName = project.getProjectName();
            client = project.getClient();

            // One extra row tells us whether the list was cut off.
            rows = new ArrayList<>(rfiReadRepository.findByProject(
                    projectName, RfiTeamType.EXTERNAL, PageRequest.of(0, maxRfis + 1)));
            if (rows.size() > maxRfis) {
                truncated = true;
                rows = new ArrayList<>(rows.subList(0, maxRfis));
            }
        } else {
            RfiShareReadRepository.RfiRow row = rfiReadRepository.findRowById(link.getRfiId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "This RFI is no longer available."));
            // Re-checked on every load: if someone flips an RFI to INTERNAL later,
            // the link stops showing it.
            if (row.getTeamType() != RfiTeamType.EXTERNAL) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This RFI is no longer available.");
            }
            projectName = row.getProjectName();
            client = link.getClient();
            rows = List.of(row);
        }

        Map<Long, List<PublicRfiShareDto.Event>> historyByRfi = loadHistory(rows);

        Instant lastChange = null;
        List<PublicRfiShareDto.Rfi> out = new ArrayList<>(rows.size());
        for (RfiShareReadRepository.RfiRow r : rows) {
            Instant created = toInstant(r.getCreatedAt());
            Instant responded = toInstant(r.getResponseReceivedAt());
            List<PublicRfiShareDto.Event> history = historyByRfi.getOrDefault(r.getId(), List.of());

            Instant activity = latest(created, responded);
            if (!history.isEmpty()) {
                activity = latest(activity, history.get(history.size() - 1).at());
            }
            lastChange = latest(lastChange, activity);

            out.add(new PublicRfiShareDto.Rfi(
                    r.getId(),
                    r.getRfiNumber(),
                    r.getSubject(),
                    r.getDiscipline() == null ? null : r.getDiscipline().name(),
                    r.getPriority() == null ? null : r.getPriority().name(),
                    r.getStatus() == null ? null : r.getStatus().name(),
                    created,
                    responded,
                    activity,
                    history));
        }

        String title = projectScope ? projectName : (out.isEmpty() ? projectName : out.get(0).rfiNumber());

        PublicRfiShareDto dto = new PublicRfiShareDto(
                link.getScope(),
                title,
                client,
                projectName,
                Instant.now(),
                lastChange,
                truncated,
                out);

        long now = System.nanoTime();
        return new Entry(
                new LinkInfo(link.getId(), link.getExpiresAt()),
                new Snapshot(dto, etagOf(dto)),
                keyOf(projectName),
                now,
                freshNanos,
                carriedTouch);
    }

    /** One query for every listed RFI's status changes, grouped + trimmed in memory. */
    private Map<Long, List<PublicRfiShareDto.Event>> loadHistory(List<RfiShareReadRepository.RfiRow> rows) {
        if (rows.isEmpty()) {
            return new HashMap<>();
        }
        List<Long> ids = rows.stream().map(RfiShareReadRepository.RfiRow::getId).toList();
        List<RfiShareHistoryReadRepository.HistoryRow> history =
                historyReadRepository.findForRfis(ids, PageRequest.of(0, MAX_HISTORY_ROWS));

        // Rows arrive newest-first: keep the newest MAX_HISTORY_PER_RFI per RFI, then flip to oldest-first.
        Map<Long, List<PublicRfiShareDto.Event>> grouped = new HashMap<>();
        for (RfiShareHistoryReadRepository.HistoryRow h : history) {
            List<PublicRfiShareDto.Event> list = grouped.computeIfAbsent(h.getRfiId(), k -> new ArrayList<>());
            if (list.size() < MAX_HISTORY_PER_RFI) {
                list.add(new PublicRfiShareDto.Event(
                        h.getOldStatus() == null ? null : h.getOldStatus().name(),
                        h.getNewStatus() == null ? null : h.getNewStatus().name(),
                        toInstant(h.getChangedAt())));
            }
        }
        grouped.values().forEach(Collections::reverse);
        return grouped;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Small helpers
    // ═════════════════════════════════════════════════════════════════════

    private Object lockFor(String token) {
        return locks[(token.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    private void rememberMiss(String token, HttpStatus status, String message) {
        if (misses.size() >= MAX_MISSES) {
            long now = System.nanoTime();
            misses.values().removeIf(m -> m.expiresNanos - now <= 0);
            if (misses.size() >= MAX_MISSES) {
                misses.clear();
            }
        }
        misses.put(token, new Miss(status, message == null ? "This link is not valid." : message,
                System.nanoTime() + MISS_NANOS));
    }

    private void trimIfNeeded() {
        if (entries.size() > MAX_ENTRIES) {
            long now = System.nanoTime();
            entries.values().removeIf(e -> now - e.loadedNanos > STALE_OK_NANOS);
        }
    }

    /**
     * Fingerprint of what the client would SEE. Excludes generatedAt (which
     * changes on every reload) so an unchanged page keeps the same ETag.
     */
    private static String etagOf(PublicRfiShareDto dto) {
        String basis = dto.scope() + "|" + dto.title() + "|" + dto.client() + "|" + dto.projectName() + "|"
                + dto.lastChange() + "|" + dto.truncated() + "|" + dto.rfis();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "\"" + hex + "\"";
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always present; fall back to something that still changes with the data.
            return "\"" + Integer.toHexString(basis.hashCode()) + "\"";
        }
    }

    /** Rfi / history store timestamps as LocalDateTime.now() in the server zone. */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static String keyOf(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
