package com.example.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Deletes messages, group messages and notifications older than a configured retention
 * window, plus the chat attachments (files) that nothing refers to any more.
 *
 * MODE (property app.retention.mode, env RETENTION_MODE):
 *   off      never delete anything
 *   dry-run  (default) only LOG what would be deleted. Nothing is removed.
 *   delete   really delete. This is PERMANENT, so take a database backup first.
 * Any other value is treated as dry-run.
 *
 * How it deletes (fast, because every statement is a round trip to the remote database):
 *   - oldest rows first, in batches of app.retention.batch-size, each batch in its own transaction
 *   - one statement removes the join-table rows (attachment links), one removes the rows themselves
 *   - stops after app.retention.max-minutes-per-run and carries on at the next scheduled run
 *
 * Attachments: only files that belonged to the rows just deleted are considered, and a file is
 * deleted only if NOTHING else still references it. "Nothing else" is not hard-coded: the
 * database itself is asked which tables have a foreign key to attachments (chat tables, RFIs, ...),
 * so a file used by an RFI, or by a newer message, is always kept. If that lookup fails, no
 * attachment is deleted at all.
 */
@Component
public class DataRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionCleaner.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    @Value("${app.retention.mode:dry-run}")
    private String mode;

    @Value("${app.retention.messages-days:90}")
    private int messageRetentionDays;

    @Value("${app.retention.group-messages-days:90}")
    private int groupMessageRetentionDays;

    @Value("${app.retention.notifications-days:90}")
    private int notificationRetentionDays;

    @Value("${app.retention.delete-orphan-attachments:true}")
    private boolean deleteOrphanAttachments;

    @Value("${app.retention.batch-size:500}")
    private int batchSize;

    @Value("${app.retention.max-minutes-per-run:10}")
    private int maxMinutesPerRun;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String lastSummary = "";

    public DataRetentionCleaner(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** One row per kind of data this job cleans. */
    private record Target(String label, String idsJpql, String deleteJpql, String joinTable, String joinColumn) {}

    private static final Target MESSAGES = new Target("messages",
            "select m.id from Message m where m.sentAt < :cutoff order by m.id",
            "delete from Message m where m.id in :ids",
            "message_attachments", "message_id");

    private static final Target GROUP_MESSAGES = new Target("group messages",
            "select m.id from GroupMessage m where m.sentAt < :cutoff order by m.id",
            "delete from GroupMessage m where m.id in :ids",
            "group_message_attachments", "group_message_id");

    private static final Target NOTIFICATIONS = new Target("notifications",
            "select n.id from Notification n where n.createdAt < :cutoff order by n.id",
            "delete from Notification n where n.id in :ids",
            "notification_attachments", "notification_id");

    /**
     * Runs daily at 03:00 server time by default (Render servers use UTC, so that is 08:30 IST).
     * Make sure your service is awake at that time. Override with app.retention.cron.
     */
    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}")
    public void purgeOldData() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Data retention: previous run is still in progress, skipping this one");
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Data retention run failed unexpectedly: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }

    /** Human-readable result of the most recent run (also logged). */
    public String getLastSummary() {
        return lastSummary;
    }

    private void runOnce() {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        boolean off = m.equals("off");
        boolean delete = m.equals("delete");
        if (!off && !delete && !m.equals("dry-run")) {
            log.warn("Data retention: unknown mode '{}', using dry-run (valid: off, dry-run, delete)", mode);
        }
        if (off) {
            lastSummary = "off";
            log.info("Data retention is OFF (app.retention.mode=off); nothing will be deleted");
            return;
        }

        LocalDateTime messageCutoff = LocalDateTime.now().minusDays(messageRetentionDays);
        LocalDateTime groupCutoff = LocalDateTime.now().minusDays(groupMessageRetentionDays);
        Instant notificationCutoff = Instant.now().minus(Duration.ofDays(notificationRetentionDays));

        log.info("Data retention [{}]: messages>{}d, groupMessages>{}d, notifications>{}d",
                delete ? "DELETE" : "DRY RUN", messageRetentionDays, groupMessageRetentionDays, notificationRetentionDays);

        if (!delete) {
            lastSummary = dryRunReport(messageCutoff, groupCutoff, notificationCutoff);
            log.warn("Data retention DRY RUN - nothing was deleted. {} To really delete, set the environment "
                    + "variable RETENTION_MODE=delete (permanent: back up the database first).", lastSummary);
            return;
        }

        long started = System.currentTimeMillis();
        long deadline = maxMinutesPerRun > 0 ? started + maxMinutesPerRun * 60_000L : Long.MAX_VALUE;
        AtomicInteger attachmentsDeleted = new AtomicInteger();
        List<String[]> guards = deleteOrphanAttachments ? discoverAttachmentReferences() : null;

        int msgs = purge(MESSAGES, messageCutoff, deadline, guards, attachmentsDeleted);
        int groupMsgs = purge(GROUP_MESSAGES, groupCutoff, deadline, guards, attachmentsDeleted);
        int notifs = purge(NOTIFICATIONS, notificationCutoff, deadline, guards, attachmentsDeleted);

        lastSummary = "deleted " + msgs + " messages, " + groupMsgs + " group messages, " + notifs
                + " notifications, " + attachmentsDeleted.get() + " attachment files";
        log.info("Data retention finished in {}s: {}", (System.currentTimeMillis() - started) / 1000, lastSummary);
    }

    // ───────────────────────── dry run ─────────────────────────

    private String dryRunReport(LocalDateTime messageCutoff, LocalDateTime groupCutoff, Instant notificationCutoff) {
        try {
            return tx.execute(status -> {
                long msgs = count("select count(m) from Message m where m.sentAt < :c", messageCutoff);
                long groupMsgs = count("select count(m) from GroupMessage m where m.sentAt < :c", groupCutoff);
                long notifs = count("select count(n) from Notification n where n.createdAt < :c", notificationCutoff);
                String files = filesReport(
                        "select count(a), coalesce(sum(a.fileSize), 0) from Attachment a where a.id in "
                      + "(select x.id from Message m join m.attachments x where m.sentAt < :c)", messageCutoff,
                        "select count(a), coalesce(sum(a.fileSize), 0) from Attachment a where a.id in "
                      + "(select x.id from GroupMessage m join m.attachments x where m.sentAt < :c)", groupCutoff,
                        "select count(a), coalesce(sum(a.fileSize), 0) from Attachment a where a.id in "
                      + "(select x.id from Notification n join n.attachments x where n.createdAt < :c)", notificationCutoff);
                return "Would delete " + msgs + " messages, " + groupMsgs + " group messages, " + notifs
                        + " notifications. " + files;
            });
        } catch (Exception e) {
            return "Could not count rows: " + e;
        }
    }

    private long count(String jpql, Object cutoff) {
        return em.createQuery(jpql, Long.class).setParameter("c", cutoff).getSingleResult();
    }

    private String filesReport(String q1, Object c1, String q2, Object c2, String q3, Object c3) {
        long[] a = fileStats(q1, c1);
        long[] b = fileStats(q2, c2);
        long[] c = fileStats(q3, c3);
        long files = a[0] + b[0] + c[0];
        long bytes = a[1] + b[1] + c[1];
        return "Files linked to them: up to " + files + " (about " + (bytes / (1024 * 1024)) + " MB). "
                + "Files still used elsewhere (e.g. by an RFI or a newer message) are always kept.";
    }

    private long[] fileStats(String jpql, Object cutoff) {
        Object[] row = em.createQuery(jpql, Object[].class).setParameter("c", cutoff).getSingleResult();
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    // ───────────────────────── real deletion ─────────────────────────

    private int purge(Target t, Object cutoff, long deadline, List<String[]> guards, AtomicInteger attachmentsDeleted) {
        int total = 0;
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    log.info("Data retention: time limit reached while deleting {}; the rest continues at the next run", t.label());
                    break;
                }
                List<Long> ids = tx.execute(s -> em.createQuery(t.idsJpql(), Long.class)
                        .setParameter("cutoff", cutoff).setMaxResults(batchSize).getResultList());
                if (ids == null || ids.isEmpty()) {
                    break;
                }

                Set<Long> attachmentIds = new HashSet<>();
                tx.executeWithoutResult(s -> {
                    for (Object row : em.createNativeQuery("select distinct attachment_id from " + t.joinTable()
                            + " where " + t.joinColumn() + " in (:ids)").setParameter("ids", ids).getResultList()) {
                        attachmentIds.add(((Number) row).longValue());
                    }
                    em.createNativeQuery("delete from " + t.joinTable() + " where " + t.joinColumn() + " in (:ids)")
                            .setParameter("ids", ids).executeUpdate();
                    em.createQuery(t.deleteJpql()).setParameter("ids", ids).executeUpdate();
                });
                total += ids.size();

                if (guards != null && !attachmentIds.isEmpty()) {
                    deleteOrphanAttachments(attachmentIds, guards, attachmentsDeleted);
                }
                if (ids.size() < batchSize) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Data retention: stopped deleting {} after {} rows because of an error: {}. "
                    + "(If it mentions reply_to_id, deploy once with APP_MAINTENANCE_FIX_REPLY_FK=true.)",
                    t.label(), total, e.toString());
        }
        return total;
    }

    /** Deletes the given attachments (and their stored bytes) unless any table still points at them. */
    private void deleteOrphanAttachments(Set<Long> candidates, List<String[]> guards, AtomicInteger deleted) {
        StringBuilder notReferenced = new StringBuilder();
        for (String[] g : guards) {
            notReferenced.append(" and not exists (select 1 from ").append(g[0])
                    .append(" x where x.").append(g[1]).append(" = a.id)");
        }
        List<Long> all = new ArrayList<>(candidates);
        for (int from = 0; from < all.size(); from += 500) {
            List<Long> chunk = all.subList(from, Math.min(from + 500, all.size()));
            try {
                tx.executeWithoutResult(s -> {
                    List<Long> orphans = new ArrayList<>();
                    for (Object row : em.createNativeQuery("select a.id from attachments a where a.id in (:ids)"
                            + notReferenced).setParameter("ids", chunk).getResultList()) {
                        orphans.add(((Number) row).longValue());
                    }
                    if (orphans.isEmpty()) {
                        return;
                    }
                    em.createNativeQuery("delete from attachment_data where attachment_id in (:ids)")
                            .setParameter("ids", orphans).executeUpdate();
                    em.createNativeQuery("delete from attachments where id in (:ids)")
                            .setParameter("ids", orphans).executeUpdate();
                    deleted.addAndGet(orphans.size());
                });
            } catch (Exception e) {
                log.warn("Data retention: left some attachment files in place because of: {}", e.toString());
            }
        }
    }

    /**
     * Asks the database which (table, column) pairs have a foreign key to attachments, so no
     * reference can be forgotten. Returns null (= do not delete any attachment) if it cannot
     * be determined with confidence.
     */
    private List<String[]> discoverAttachmentReferences() {
        try {
            List<String[]> found = tx.execute(s -> em.unwrap(Session.class).doReturningWork(conn -> {
                DatabaseMetaData md = conn.getMetaData();
                List<String[]> out = new ArrayList<>();
                for (String table : new String[]{"attachments", "ATTACHMENTS"}) {
                    try (ResultSet rs = md.getExportedKeys(conn.getCatalog(), null, table)) {
                        while (rs.next()) {
                            out.add(new String[]{rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME")});
                        }
                    }
                    if (!out.isEmpty()) {
                        break;
                    }
                }
                return out;
            }));
            List<String[]> guards = new ArrayList<>();
            for (String[] g : found) {
                if (g[0] == null || g[1] == null || !SAFE_IDENTIFIER.matcher(g[0]).matches()
                        || !SAFE_IDENTIFIER.matcher(g[1]).matches()) {
                    log.warn("Data retention: unexpected table/column name in foreign key list; not deleting attachment files");
                    return null;
                }
                if (g[0].equalsIgnoreCase("attachment_data")) {
                    continue; // the file bytes themselves: removed together with their attachment
                }
                guards.add(g);
            }
            if (guards.isEmpty()) {
                log.warn("Data retention: could not see which tables reference attachments; not deleting attachment files");
                return null;
            }
            return guards;
        } catch (Exception e) {
            log.warn("Data retention: could not check attachment references ({}); not deleting attachment files", e.toString());
            return null;
        }
    }
}