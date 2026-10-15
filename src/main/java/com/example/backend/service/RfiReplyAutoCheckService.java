package com.example.backend.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.backend.dto.RfiSyncResultDto;
import com.example.backend.entity.RfiStatus;
import com.example.backend.repository.RfiRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Automatic "did a reply arrive?" check — the same import + status change +
 * notification that the manual "Sync Gmail" button runs, just triggered for you.
 *
 * Built to cost (almost) nothing:
 *   - No background thread of its own. It runs only when something asks (the open app,
 *     or the optional RfiReplyBackgroundChecker).
 *   - One tiny DB query (id + thread id only). No watched RFIs -> returns right there,
 *     Gmail is never called.
 *   - ONE Gmail call per user per check, ids only (no bodies/attachments). The full
 *     per-RFI sync runs only for a thread that really has a not-yet-imported message.
 *   - Duplicate imports are impossible (existing gmail_message_id unique index), so a
 *     check overlapping a manual sync is harmless.
 *   - Per-user throttle (in memory) so extra tabs / quick re-checks do nothing.
 *   - DB writes happen only when a reply is actually imported.
 *
 * Limitation (kept on purpose): the quick probe matches by Gmail thread id. A reply that
 * Gmail put in a brand-new thread is still found by the manual Sync Gmail button
 * (it also searches by subject).
 */
@Service
@Slf4j
public class RfiReplyAutoCheckService {

    /** Same idea as the manual sync: closed/cancelled/draft RFIs are never moved by an email. */
    private static final Set<RfiStatus> NOT_WATCHED = EnumSet.of(RfiStatus.DRAFT, RfiStatus.CLOSED, RfiStatus.CANCELLED);

    private static final long MIN_GAP_MS = 30_000L;           // at most one real check / user / 30 s
    private static final long FIRST_LOOKBACK_S = 7L * 86_400; // first check after a restart looks back 7 days
    private static final long OVERLAP_S = 600L;               // re-scan the last 10 min each time (idempotent)

    /** What the caller (and the UI) needs to know. */
    public record CheckResult(int waiting, int imported) {
    }

    @Autowired
    private RfiRepository rfiRepository;

    @Autowired
    private RfiGmailSyncService rfiGmailSyncService;

    // Tiny in-memory state (a few longs per active user). Lost on restart = harmless.
    private final Map<String, Long> lastRunMs = new ConcurrentHashMap<>();
    private final Map<String, Long> scannedUpToEpochS = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public CheckResult checkUser(String username) {
        if (username == null || username.isBlank()) {
            return new CheckResult(0, 0);
        }
        String user = username.trim();

        long now = System.currentTimeMillis();
        Long last = lastRunMs.get(user);
        if (last != null && now - last < MIN_GAP_MS) {
            return new CheckResult(-1, 0); // throttled: -1 = "unchanged, ask again later"
        }
        if (!running.add(user)) {
            return new CheckResult(-1, 0);
        }
        try {
            lastRunMs.put(user, now);

            List<RfiRepository.WatchedRfiRow> watched = rfiRepository.findWatchedBySender(user, NOT_WATCHED);
            if (watched.isEmpty()) {
                return new CheckResult(0, 0);
            }
            Map<String, Long> rfiByThread = new HashMap<>();
            for (RfiRepository.WatchedRfiRow row : watched) {
                rfiByThread.putIfAbsent(row.getGmailThreadId(), row.getId());
            }

            long nowS = Instant.now().getEpochSecond();
            long after = scannedUpToEpochS.getOrDefault(user, nowS - FIRST_LOOKBACK_S);

            Set<String> hits = rfiGmailSyncService.findThreadsWithNewMail(user, after, rfiByThread.keySet());
            if (hits == null) {
                return new CheckResult(watched.size(), 0); // probe couldn't run; keep the old window and retry later
            }

            int imported = 0;
            boolean allOk = true;
            for (String threadId : hits) {
                RfiSyncResultDto result = rfiGmailSyncService.syncRfi(rfiByThread.get(threadId));
                if (Boolean.TRUE.equals(result.getSuccess())) {
                    imported += result.getNewMessagesCount() == null ? 0 : result.getNewMessagesCount();
                } else {
                    allOk = false; // keep the window so the next check retries this thread
                }
            }
            if (allOk) {
                scannedUpToEpochS.put(user, nowS - OVERLAP_S);
            }
            if (imported > 0) {
                log.info("Auto reply check: imported {} new message(s) for {}", imported, user);
            }
            return new CheckResult(watched.size(), imported);
        } catch (Exception e) {
            log.warn("Auto reply check failed for {}: {}", user, e.getMessage());
            return new CheckResult(0, 0);
        } finally {
            running.remove(user);
        }
    }

    /** Senders that currently have RFIs worth watching (for the optional background check). */
    public List<String> sendersToCheck() {
        return rfiRepository.findSendersWithWatchedRfis(NOT_WATCHED);
    }
}