package com.example.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.dto.PublicShareDto;
import com.example.backend.entity.ChangeOrder;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.entity.ShareLink;
import com.example.backend.entity.ShareScope;
import com.example.backend.repository.ChangeOrderRepository;
import com.example.backend.repository.ProjectStatusRepository;
import com.example.backend.repository.ShareLinkRepository;

/**
 * Builds the read-only snapshot behind GET /public/share/{token}, with a
 * small in-memory cache so the database is barely touched.
 *
 * WHY: the client's page refreshes itself every ~30 s while it is open. Without
 * a cache every refresh from every viewer ran 3+ queries (link, projects,
 * change orders) plus a write, which keeps the Railway database busy (and
 * billed) around the clock.
 *
 * HOW:
 *  - One snapshot per token is kept in memory for FRESH_SECONDS. However many
 *    people have the link open, the DB is queried at most once per token per
 *    30 s (concurrent requests share a single load).
 *  - Revoking a link evicts it right after the revoke commits, so it stops
 *    working immediately. Expiry is checked on every request (no DB needed).
 *  - "last viewed" is written at most once per TOUCH_INTERVAL_SECONDS per link,
 *    with a tiny UPDATE (no entity load).
 *  - Unknown / revoked tokens are remembered for a minute so scanners or stale
 *    tabs can't hammer the DB.
 *  - If the DB is briefly unreachable, the last good snapshot is served
 *    (for up to STALE_OK_MINUTES) instead of an error.
 *  - Each snapshot carries an ETag so the browser can ask "anything new?" and
 *    get an empty 304 reply (saves Render bandwidth) - see PublicShareController.
 *
 * Assumes ONE backend instance (Render free/starter). With several instances
 * each keeps its own cache; revoke would then take up to FRESH_SECONDS to
 * reach the others.
 *
 * Deliberately NOT @Transactional: a cache hit must not open a DB transaction
 * (that alone costs connection round-trips). Only the actual load uses a
 * short read-only transaction via TransactionTemplate.
 */
@Service
public class PublicShareService {

    private static final Logger log = LoggerFactory.getLogger(PublicShareService.class);

    // ── Tuning ────────────────────────────────────────────────────────────
    /** How long a snapshot is served from memory before the DB is asked again. */
    private static final long FRESH_SECONDS = 30;
    /** How long an unknown / revoked / expired token is remembered. */
    private static final long MISS_SECONDS = 60;
    /** Minimum gap between "last viewed" writes for one link. */
    private static final long TOUCH_INTERVAL_SECONDS = 300;
    /** If the DB is down, keep serving the last good snapshot this long. */
    private static final long STALE_OK_MINUTES = 10;
    /** After a failed reload, wait this long before trying the DB again. */
    private static final long RETRY_AFTER_FAILURE_SECONDS = 10;

    private static final int MAX_ENTRIES = 500;
    private static final int MAX_MISSES = 2000;
    private static final int LOCK_STRIPES = 32;

    /** Real tokens are 32 URL-safe chars; reject junk without touching the DB. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private static final long FRESH_NANOS = TimeUnit.SECONDS.toNanos(FRESH_SECONDS);
    private static final long MISS_NANOS = TimeUnit.SECONDS.toNanos(MISS_SECONDS);
    private static final long STALE_OK_NANOS = TimeUnit.MINUTES.toNanos(STALE_OK_MINUTES);
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(RETRY_AFTER_FAILURE_SECONDS);

    // ── Public result type ────────────────────────────────────────────────
    /** A snapshot plus its ETag (a fingerprint of the data, not of the time). */
    public record Snapshot(PublicShareDto dto, String etag) {
    }

    // ── Cache internals ───────────────────────────────────────────────────
    private record LinkInfo(Long id, Instant expiresAt) {
    }

    private static final class Entry {
        final LinkInfo link;
        final Snapshot snapshot;
        final long loadedNanos;
        volatile long freshUntil;
        volatile Instant lastTouch;

        Entry(LinkInfo link, Snapshot snapshot, long loadedNanos, Instant lastTouch) {
            this.link = link;
            this.snapshot = snapshot;
            this.loadedNanos = loadedNanos;
            this.freshUntil = loadedNanos + FRESH_NANOS;
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

    private final ShareLinkRepository shareLinkRepository;
    private final ProjectStatusRepository projectStatusRepository;
    private final ChangeOrderRepository changeOrderRepository;
    private final TransactionTemplate readTx;

    public PublicShareService(ShareLinkRepository shareLinkRepository,
                              ProjectStatusRepository projectStatusRepository,
                              ChangeOrderRepository changeOrderRepository,
                              PlatformTransactionManager transactionManager) {
        this.shareLinkRepository = shareLinkRepository;
        this.projectStatusRepository = projectStatusRepository;
        this.changeOrderRepository = changeOrderRepository;
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
     * Throws ResponseStatusException 404 (unknown token / project gone) or
     * 410 (revoked / expired).
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(token);
                }
            });
        } else {
            evict(token);
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
            shareLinkRepository.touchLastViewed(e.link.id(), now);
        } catch (RuntimeException ex) {
            // Never fail a client's page just because the "last viewed" note couldn't be saved.
            log.warn("Could not update last_viewed_at for share link {}: {}", e.link.id(), ex.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Loading from the database
    // ═════════════════════════════════════════════════════════════════════

    private Entry reload(String token, Entry previous) {
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
                log.warn("Share snapshot reload failed, serving previous copy: {}", e.toString());
                previous.freshUntil = System.nanoTime() + RETRY_NANOS;
                return previous;
            }
            throw e;
        }
    }

    /** Runs inside a short read-only transaction. */
    private Entry load(String token, Instant carriedTouch) {
        ShareLink link = shareLinkRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is not valid."));

        if (!link.isActive()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has been revoked.");
        }
        if (link.getExpiresAt() != null && Instant.now().isAfter(link.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link has expired.");
        }

        List<ProjectStatus> projects = loadProjects(link);
        Map<String, List<ChangeOrder>> changeOrdersByProject = loadChangeOrders(projects);
        boolean details = link.isShowDetails();

        Instant lastChange = null;
        List<PublicShareDto.Project> out = new ArrayList<>();

        for (ProjectStatus p : projects) {
            List<ChangeOrder> cos = changeOrdersByProject.getOrDefault(key(p.getProjectName()), List.of());
            List<PublicShareDto.ChangeOrder> coDtos = new ArrayList<>();

            for (ChangeOrder co : cos) {
                Instant coUpdated = toInstant(co.getUpdatedAt());
                lastChange = latest(lastChange, coUpdated);
                coDtos.add(new PublicShareDto.ChangeOrder(
                        co.getId(),
                        co.getCo(),
                        co.getDescription(),
                        co.getStatus(),
                        co.getAmount(),
                        dateToString(co.getIfaDate()),
                        co.getIfaPer(),
                        dateToString(co.getIffDate()),
                        co.getIffPer(),
                        details ? co.getRemarks() : null,
                        coUpdated));
            }

            Instant pUpdated = toInstant(p.getUpdatedAt());
            lastChange = latest(lastChange, pUpdated);

            out.add(new PublicShareDto.Project(
                    p.getId(),
                    p.getClient(),
                    p.getProjectName(),
                    p.getJobNumber(),
                    p.getYear(),
                    p.getProjectManager(),
                    p.getApprovalStatus(),
                    p.getFabStatus(),
                    p.getIfaDate(),
                    p.getIfcDate(),
                    details ? p.getTeam() : null,
                    details ? p.getRemarks() : null,
                    pUpdated,
                    coDtos));
        }

        String title = (link.getScope() == ShareScope.PROJECT && !out.isEmpty())
                ? out.get(0).projectName()
                : link.getClient();

        PublicShareDto dto = new PublicShareDto(
                link.getScope().name(),
                title,
                link.getClient(),
                link.getShareYear(),
                details,
                Instant.now(),
                lastChange,
                out);

        long now = System.nanoTime();
        return new Entry(
                new LinkInfo(link.getId(), link.getExpiresAt()),
                new Snapshot(dto, etagOf(dto)),
                now,
                carriedTouch);
    }

    private List<ProjectStatus> loadProjects(ShareLink link) {
        List<ProjectStatus> projects;

        if (link.getScope() == ShareScope.PROJECT) {
            ProjectStatus p = projectStatusRepository.findById(link.getProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "This project is no longer available."));
            projects = new ArrayList<>(List.of(p));
        } else {
            projects = projectsForClient(link.getClient(), link.getShareYear());
        }

        // Newest year first, then alphabetical by project name.
        projects.sort(
                Comparator.comparing((ProjectStatus p) -> nullToEmpty(p.getYear())).reversed()
                        .thenComparing(p -> nullToEmpty(p.getProjectName()), String.CASE_INSENSITIVE_ORDER));
        return projects;
    }

    /**
     * Projects of a client, optionally limited to one year. The DB comparison
     * can be case-insensitive (MySQL default collation) whereas the Projects
     * page groups clients with exact string equality, so filter again here.
     */
    private List<ProjectStatus> projectsForClient(String client, String year) {
        return projectStatusRepository.findByClient(client).stream()
                .filter(p -> client.equals(p.getClient()))
                .filter(p -> year == null || year.equals(p.getYear()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** One query for all projects' change orders, grouped by normalised project name. */
    private Map<String, List<ChangeOrder>> loadChangeOrders(List<ProjectStatus> projects) {
        List<String> names = projects.stream()
                .map(ProjectStatus::getProjectName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();

        if (names.isEmpty()) {
            return new HashMap<>();
        }

        return changeOrderRepository.findByProjectNameInOrderByIdAsc(names).stream()
                .collect(Collectors.groupingBy(co -> key(co.getProjectName())));
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
    private static String etagOf(PublicShareDto dto) {
        String basis = dto.scope() + "|" + dto.title() + "|" + dto.client() + "|" + dto.year() + "|"
                + dto.detailsIncluded() + "|" + dto.lastChange() + "|" + dto.projects();
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

    /** ProjectStatus / ChangeOrder store timestamps as LocalDateTime.now() in the server zone. */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static String dateToString(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}