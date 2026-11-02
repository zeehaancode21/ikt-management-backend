package com.example.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.backend.Exception.ResourceNotFoundException;
import com.example.backend.dto.RfiSyncResultDto;
import com.example.backend.entity.Attachment;
import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiActionSource;
import com.example.backend.entity.RfiEmailDirection;
import com.example.backend.entity.RfiEmailMessage;
import com.example.backend.entity.RfiParticipantRole;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.User;
import com.example.backend.repository.RfiEmailMessageRepository;
import com.example.backend.repository.RfiRepository;
import com.example.backend.repository.RfiStatusHistoryRepository;
import com.example.backend.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Implements "Sync Gmail" / "Check for Replies": pulls every message in an
 * RFI's Gmail thread, stores any that aren't already recorded, and applies
 * the (deliberately conservative) status effects of an incoming reply.
 *
 * HOW A SYNC NOW RUNS (changed from the previous version)
 *
 *   Phase 1 - Gmail only, NO database transaction / connection held
 *       resolve credentials -> (optional) subject search -> fetch thread(s) -> ONE
 *       "which of these ids are already stored?" query.
 *   Phase 2 - one SHORT transaction per new message
 *       reload the RFI -> save the message (+ attachment pointers) -> apply the status effect.
 *
 * Why: with hikari maximum-pool-size=4, holding a connection while waiting for Google meant four
 * simultaneous syncs froze the whole app. And a duplicate-key error inside one big transaction
 * marked it rollback-only, so every message imported before it was thrown away.
 * Now a duplicate only skips that one message.
 *
 * Matching strategy, in order of preference:
 *   1. Rfi.gmailThreadId (set at send time) - the primary, reliable match.
 *   2. No thread id yet (legacy RFIs): ONE subject search. Exactly one hit is adopted as the
 *      thread id; several hits are all read but not adopted (never guessed).
 *   3. "deep" sync (the manual Sync Gmail button on ONE RFI): additionally searches by subject,
 *      to find a reply Gmail put into a brand-new thread. The automatic check and "sync all"
 *      skip that extra search (1 Gmail call per RFI instead of 2).
 */
@Service
@Slf4j
public class RfiGmailSyncService {

    // A reply never moves a CLOSED or CANCELLED RFI on its own — the user
    // has to Reopen it first. See the "RFI STATUS CONTROL" requirement.
    private static final Set<RfiStatus> AUTO_TRANSITION_BLOCKED = EnumSet.of(RfiStatus.CLOSED, RfiStatus.CANCELLED);

    /** "Sync all" does not touch these (nothing can change for them). */
    private static final Set<RfiStatus> SYNC_ALL_SKIPPED = EnumSet.of(RfiStatus.CLOSED, RfiStatus.CANCELLED);

    @Autowired
    private RfiRepository rfiRepository;

    @Autowired
    private RfiEmailMessageRepository emailMessageRepository;

    @Autowired
    private RfiStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private GmailEmailService emailService;

    @Autowired
    private FileStorageService fileStorageService;

    // Decides whether an email sender is on the RFI's To/CC list.
    @Autowired
    private RfiAccessService rfiAccessService;

    @Autowired
    private RfiCcNotificationService rfiCcNotificationService;

    // The single place that changes an RFI's status + writes history + notifies.
    @Autowired
    private RfiStatusTransitionService rfiStatusTransitionService;

    // A sync authenticates as the RFI's own sender (RfiSettings), not a global account.
    @Autowired
    private RfiSettingsService rfiSettingsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** "Sync all" handles at most this many RFIs per request (keeps one HTTP call under the proxy timeout). */
    @Value("${app.rfi.sync-all.max:50}")
    private int syncAllMax = 50;

    private volatile TransactionTemplate txTemplate;

    private TransactionTemplate tx() {
        TransactionTemplate t = txTemplate;
        if (t == null) {
            t = new TransactionTemplate(transactionManager);
            txTemplate = t;
        }
        return t;
    }

    /** Refresh token + Client ID/Secret + Gmail address for the account THIS RFI was actually sent from. */
    private static final class SenderCredentials {
        final Long userId;
        final String username;
        final String refreshToken;
        final String clientId;
        final String clientSecret;
        final String fromEmail;
        SenderCredentials(Long userId, String username, String refreshToken, String clientId, String clientSecret, String fromEmail) {
            this.userId = userId;
            this.username = username;
            this.refreshToken = refreshToken;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.fromEmail = fromEmail;
        }
    }

    /**
     * Resolves the Gmail account to sync AS — always the RFI's own sender
     * (rfi.getSentBy()), from THEIR OWN RfiSettings row (RFI Settings screen).
     */
    private SenderCredentials resolveSenderCredentials(String sentBy) throws Exception {
        if (sentBy == null || sentBy.isBlank()) {
            throw new Exception("This RFI has no recorded sender to sync Gmail as");
        }
        String username = sentBy.trim();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new Exception("No account found for: " + username
                        + " (check for a case or whitespace mismatch against the users table)"));

        if (!rfiSettingsService.hasValidSettings(user.getId())) {
            throw new Exception(username + " needs to (re)connect Gmail (email, Client ID, Client Secret "
                    + "and token) in RFI Settings before this RFI can sync — one or more of those fields is "
                    + "missing or the token was previously marked invalid for this account specifically.");
        }

        String token = rfiSettingsService.getDecryptedToken(user.getId());
        String fromEmail = rfiSettingsService.getGmailEmail(user.getId());
        String clientId = rfiSettingsService.getGmailClientId(user.getId());
        String clientSecret = rfiSettingsService.getDecryptedClientSecret(user.getId());
        return new SenderCredentials(user.getId(), username, token, clientId, clientSecret, fromEmail);
    }

    /** Resolves each sender once per "sync all" run (the same person sent most RFIs of a project). */
    private final class CredentialsCache {
        private final Map<String, SenderCredentials> ok = new HashMap<>();
        private final Map<String, Exception> failed = new HashMap<>();

        SenderCredentials get(String sentBy) throws Exception {
            String key = sentBy == null ? "" : sentBy.trim();
            if (ok.containsKey(key)) {
                return ok.get(key);
            }
            if (failed.containsKey(key)) {
                throw failed.get(key);
            }
            try {
                SenderCredentials c = resolveSenderCredentials(sentBy);
                ok.put(key, c);
                return c;
            } catch (Exception e) {
                failed.put(key, e);
                throw e;
            }
        }
    }

    private boolean looksLikeInvalidGrant(Throwable e) {
        return RfiErrors.isInvalidGrant(e);
    }

    /**
     * Automatic-check probe (see RfiReplyAutoCheckService). Asks THIS user's Gmail
     * once for recent incoming mail and returns the subset of {@code watchedThreadIds}
     * that has a message we have not imported yet. Not transactional on purpose — no DB
     * connection is held while Gmail is being called. Never throws: returns null when the
     * probe could not run (caller retries later, the manual Sync Gmail button still reports
     * the real error), otherwise the (possibly empty) set of threads with new mail.
     */
    public Set<String> findThreadsWithNewMail(String username, long afterEpochSeconds, Set<String> watchedThreadIds) {
        Set<String> result = new LinkedHashSet<>();
        try {
            SenderCredentials creds = resolveSenderCredentials(username);
            List<GmailEmailService.MessageRef> refs = emailService.listRecentIncomingRefs(
                    creds.refreshToken, creds.clientId, creds.clientSecret, afterEpochSeconds, 500);

            // Only refs in a watched thread matter; check them against the DB with ONE query.
            List<GmailEmailService.MessageRef> relevant = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (GmailEmailService.MessageRef ref : refs) {
                if (ref.threadId() != null && watchedThreadIds.contains(ref.threadId()) && ref.messageId() != null) {
                    relevant.add(ref);
                    ids.add(ref.messageId());
                }
            }
            Set<String> alreadyStored = ids.isEmpty()
                    ? new HashSet<>()
                    : new HashSet<>(emailMessageRepository.findExistingGmailMessageIds(ids));
            for (GmailEmailService.MessageRef ref : relevant) {
                if (!alreadyStored.contains(ref.messageId())) {
                    result.add(ref.threadId());
                }
            }
        } catch (Exception e) {
            log.warn("Auto reply check could not run for {}: {}", username, e.getMessage());
            if (looksLikeInvalidGrant(e)) {
                try {
                    userRepository.findByUsername(username.trim())
                            .ifPresent(u -> rfiSettingsService.markTokenAsInvalid(u.getId()));
                } catch (Exception ignored) {
                    // best effort — same handling as a failed manual sync
                }
            }
            return null;
        }
        return result;
    }

    /** Manually triggered sync for a single RFI ("Sync Gmail" button on the RFI detail view). Deep search on. */
    public RfiSyncResultDto syncRfi(Long rfiId) {
        return syncRfi(rfiId, true);
    }

    /**
     * @param deepSearch true = also look for a reply that Gmail put into a different thread
     *                   (one extra Gmail call). The automatic check passes false.
     */
    public RfiSyncResultDto syncRfi(Long rfiId, boolean deepSearch) {
        Rfi rfi = rfiRepository.findById(rfiId)
                .orElseThrow(() -> new ResourceNotFoundException("RFI not found with ID: " + rfiId));
        return syncOne(rfi, deepSearch, null);
    }

    /**
     * Syncs the open RFIs of a project for ONE caller ("Sync all" on the RFI History tab).
     *  - CLOSED / CANCELLED RFIs are skipped
     *  - RFIs the caller is not a participant of (not To / CC / sender) are skipped - the sync runs
     *    against the RFI sender's Gmail mailbox, so it is not something an outsider may trigger
     *  - at most app.rfi.sync-all.max RFIs per call (default 50)
     */
    public List<RfiSyncResultDto> syncAllForProject(String projectName, String username) {
        List<Rfi> rfis = rfiRepository.findActiveByProjectName(projectName, SYNC_ALL_SKIPPED);
        // Fills in canModify for this caller (one user lookup for the whole list).
        rfiAccessService.annotate(rfis, username);

        CredentialsCache creds = new CredentialsCache();
        List<RfiSyncResultDto> results = new ArrayList<>();
        for (Rfi rfi : rfis) {
            if (!rfi.isCanModify()) {
                continue;
            }
            if (results.size() >= syncAllMax) {
                log.info("Sync all for project '{}' stopped at {} RFIs (app.rfi.sync-all.max)", projectName, syncAllMax);
                break;
            }
            results.add(syncOne(rfi, false, creds));
        }
        return results;
    }

    // ── Internals ────────────────────────────────────────────────────────

    private RfiSyncResultDto syncOne(Rfi rfi, boolean deepSearch, CredentialsCache credsCache) {
        try {
            // ── Phase 1: Gmail only. No transaction is open here. ──────────────────────────
            SenderCredentials creds = credsCache != null
                    ? credsCache.get(rfi.getSentBy())
                    : resolveSenderCredentials(rfi.getSentBy());

            String subject = rfi.getGeneratedEmailSubject();
            boolean hasSubject = subject != null && !subject.isBlank();
            boolean hasThread = rfi.getGmailThreadId() != null && !rfi.getGmailThreadId().isBlank();

            Set<String> candidateThreadIds = new LinkedHashSet<>();
            String threadToAdopt = null;
            if (hasThread) {
                candidateThreadIds.add(rfi.getGmailThreadId());
                if (deepSearch && hasSubject) {
                    candidateThreadIds.addAll(emailService.searchThreadIdsBySubject(
                            subject, creds.refreshToken, creds.clientId, creds.clientSecret));
                }
            } else if (hasSubject) {
                List<String> found = emailService.searchThreadIdsBySubject(
                        subject, creds.refreshToken, creds.clientId, creds.clientSecret);
                if (found.size() == 1) {
                    threadToAdopt = found.get(0);
                } else if (found.size() > 1) {
                    log.warn("RFI {} ({}): subject search matched {} Gmail threads; not adopting any as the thread id.",
                            rfi.getRfiNumber(), rfi.getProjectName(), found.size());
                }
                candidateThreadIds.addAll(found);
            }

            if (candidateThreadIds.isEmpty()) {
                // Nothing to sync yet (no thread known) — not an error, just a no-op.
                return new RfiSyncResultDto(rfi.getId(), rfi.getRfiNumber(), true, 0, null, rfi);
            }

            List<GmailFetchedMessage> fetched = new ArrayList<>();
            for (String threadId : candidateThreadIds) {
                fetched.addAll(emailService.fetchThreadMessages(
                        threadId, creds.refreshToken, creds.clientId, creds.clientSecret));
            }
            fetched.sort(Comparator.comparing(GmailFetchedMessage::getMessageDate,
                    Comparator.nullsFirst(Comparator.naturalOrder())));

            // ONE query: which of these Gmail ids are already stored?
            Set<String> ids = new LinkedHashSet<>();
            for (GmailFetchedMessage m : fetched) {
                if (m.getGmailMessageId() != null) {
                    ids.add(m.getGmailMessageId());
                }
            }
            Set<String> alreadyStored = ids.isEmpty()
                    ? new HashSet<>()
                    : new HashSet<>(emailMessageRepository.findExistingGmailMessageIds(ids));

            // ── Phase 2: short transactions. ───────────────────────────────────────────────
            if (threadToAdopt != null) {
                adoptThreadId(rfi.getId(), threadToAdopt);
            }

            int newCount = 0;
            Set<String> seenInThisRun = new HashSet<>();
            for (GmailFetchedMessage msg : fetched) {
                if (msg.getGmailMessageId() == null
                        || alreadyStored.contains(msg.getGmailMessageId())
                        || !seenInThisRun.add(msg.getGmailMessageId())) {
                    continue; // already imported by an earlier sync (or listed twice by two threads)
                }
                if (importMessage(rfi.getId(), msg, creds)) {
                    newCount++;
                }
            }

            // Fresh copy for the response (status / thread id / responseReceivedAt may have changed).
            Rfi updated = newCount > 0 || threadToAdopt != null
                    ? rfiRepository.findById(rfi.getId()).orElse(rfi)
                    : rfi;
            return new RfiSyncResultDto(rfi.getId(), rfi.getRfiNumber(), true, newCount, null, updated);
        } catch (Exception e) {
            log.warn("Gmail sync failed for RFI {} (id={}, sentBy={}): {}",
                    rfi.getRfiNumber(), rfi.getId(), rfi.getSentBy(), e.getMessage());

            String errorMessage = friendlySyncError(e);
            if (looksLikeInvalidGrant(e)) {
                // Flip THIS user's token to invalid (never a global flag) so the RFI Settings
                // screen can surface "reconnect Gmail" for them specifically.
                try {
                    if (rfi.getSentBy() != null) {
                        User user = userRepository.findByUsername(rfi.getSentBy().trim()).orElse(null);
                        if (user != null) {
                            rfiSettingsService.markTokenAsInvalid(user.getId());
                        }
                    }
                } catch (Exception markFailure) {
                    log.warn("Could not mark token invalid for RFI {}: {}", rfi.getRfiNumber(), markFailure.getMessage());
                }
                errorMessage = (rfi.getSentBy() != null ? rfi.getSentBy().trim() : "This user")
                        + "'s Gmail connection expired. They need to reconnect Gmail in RFI Settings "
                        + "(per account - other users are not affected).";
            }
            return new RfiSyncResultDto(rfi.getId(), rfi.getRfiNumber(), false, 0, errorMessage, rfi);
        }
    }

    /** Messages written by our own checks (missing sender, not connected...) are already user-friendly; raw errors are not. */
    private static String friendlySyncError(Exception e) {
        String m = e.getMessage();
        boolean ours = m != null && (m.startsWith("This RFI has no recorded sender")
                || m.contains("needs to (re)connect Gmail")
                || m.startsWith("No account found for"));
        return ours ? m : "Could not read this RFI's Gmail thread right now. Please try again.";
    }

    private void adoptThreadId(Long rfiId, String threadId) {
        tx().executeWithoutResult(status -> {
            Rfi managed = rfiRepository.findById(rfiId).orElse(null);
            if (managed != null && (managed.getGmailThreadId() == null || managed.getGmailThreadId().isBlank())) {
                managed.setGmailThreadId(threadId);
                rfiRepository.save(managed);
            }
        });
    }

    /**
     * Saves ONE message (+ its attachment pointers) and applies its status effect, in its own short
     * transaction. Returns false when the message was skipped (already stored / duplicate).
     */
    private boolean importMessage(Long rfiId, GmailFetchedMessage msg, SenderCredentials creds) {
        try {
            Boolean imported = tx().execute(status -> {
                Rfi rfi = rfiRepository.findById(rfiId).orElse(null);
                if (rfi == null) {
                    return Boolean.FALSE;
                }

                // A reply a CC person sent from the app was already stored when they sent it
                // (their Gmail id differs from the one in this mailbox, the Message-ID does not).
                if (msg.getRfcMessageId() != null
                        && emailMessageRepository.existsByRfiIdAndRfcMessageId(rfiId, msg.getRfcMessageId())) {
                    return Boolean.FALSE;
                }

                // The From header is usually "Name <a@b.com>", so compare plain addresses.
                String ourNormalized = RfiAccessService.normalizeEmail(creds.fromEmail);
                String msgFromNormalized = RfiAccessService.normalizeEmail(msg.getFromAddress());
                RfiEmailDirection direction =
                        (ourNormalized != null && ourNormalized.equals(msgFromNormalized))
                        ? RfiEmailDirection.OUTGOING
                        : RfiEmailDirection.INCOMING;

                if (direction == RfiEmailDirection.INCOMING
                        && msg.getGmailThreadId() != null
                        && !msg.getGmailThreadId().equals(rfi.getGmailThreadId())) {
                    // The reply landed in a Gmail thread different from the one we tracked at send
                    // time. Adopt the thread the actual reply is in so future syncs find it directly.
                    rfi.setGmailThreadId(msg.getGmailThreadId());
                }

                RfiEmailMessage entity = new RfiEmailMessage();
                entity.setRfi(rfi);
                entity.setDirection(direction);
                entity.setGmailMessageId(msg.getGmailMessageId());
                entity.setGmailThreadId(msg.getGmailThreadId());
                entity.setInReplyTo(msg.getInReplyTo());
                entity.setReferences(msg.getReferences());
                entity.setFromAddress(msg.getFromAddress());
                entity.setToAddress(msg.getToAddress());
                entity.setCcAddress(msg.getCcAddress());
                entity.setRfcMessageId(msg.getRfcMessageId());
                entity.setSubject(msg.getSubject());
                entity.setBodyText(msg.getBodyText());
                entity.setSnippet(msg.getSnippet());
                entity.setMessageDate(msg.getMessageDate() != null ? msg.getMessageDate() : LocalDateTime.now());
                entity.setAttachments(recordAttachments(msg, rfi.getSentBy(), creds));

                // Flush now so a duplicate key surfaces HERE (and rolls back only this message).
                emailMessageRepository.saveAndFlush(entity);

                if (direction == RfiEmailDirection.INCOMING) {
                    applyIncomingReplyEffects(rfi, msg);
                }
                rfiRepository.save(rfi);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(imported);
        } catch (DataIntegrityViolationException raceLostToAnotherSync) {
            log.debug("Message {} was imported by a parallel sync - skipped", msg.getGmailMessageId());
            return false;
        }
    }

    /**
     * Records the files of one Gmail message WITHOUT downloading them: only a small pointer row is
     * saved (message id + part id + attachment id + whose mailbox). When someone clicks View /
     * Download, AttachmentController streams the file from the Gmail thread on demand.
     */
    private List<Attachment> recordAttachments(GmailFetchedMessage msg, String uploadedBy, SenderCredentials creds) {
        List<Attachment> result = new ArrayList<>();
        for (GmailFetchedAttachment att : msg.getAttachments()) {
            try {
                Attachment saved = fileStorageService.storeGmailReference(
                        att.getFilename(), att.getMimeType(), att.getSize(),
                        uploadedBy != null ? uploadedBy : "gmail-sync",
                        msg.getGmailMessageId(), att.getGmailAttachmentId(), att.getPartId(),
                        creds.username);
                result.add(saved);
            } catch (Exception e) {
                // Don't fail the whole reply just because one attachment couldn't be recorded.
                log.warn("Failed to record attachment '{}' on Gmail message {}: {}",
                        att.getFilename(), msg.getGmailMessageId(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Applies an inbound email to the RFI's global status.
     *
     * The sender's address is checked against the RFI's To and CC lists:
     *   - not on either list  -> the email is kept in the timeline (already saved),
     *                            but it can NOT change the status (read-only rule)
     *   - CC participant      -> RESUBMITTED
     *   - TO participant      -> RESPONSE_RECEIVED, or RESUBMITTED when the email is
     *                            marked as a resubmission ([Resubmit] in the subject,
     *                            or the word "resubmit" in the first line)
     *
     * A CLOSED or CANCELLED RFI is never moved by an email - someone on the
     * To/CC list has to Reopen it first. This NEVER sets CLOSED.
     */
    private void applyIncomingReplyEffects(Rfi rfi, GmailFetchedMessage msg) {
        String senderEmail = RfiAccessService.normalizeEmail(msg.getFromAddress());
        RfiParticipantRole role = rfiAccessService.roleOfEmail(rfi, senderEmail);

        // Tell the person who SENT the RFI that a reply arrived (they are usually not on To/CC).
        try {
            String rfiSender = rfi.getSentBy();
            if (rfiSender != null && !rfiSender.isBlank()
                    && rfiAccessService.roleOfUser(rfi, rfiSender) == RfiParticipantRole.SENDER) {
                final String notifyTo = rfiSender;
                final String title = "Reply received on " + rfi.getRfiNumber();
                final String text = "A reply was received on RFI " + rfi.getRfiNumber() + " (" + rfi.getProjectName()
                        + ") from " + (senderEmail != null ? senderEmail : "the recipient") + ".";
                // After commit, so the browser reloads the already-updated status.
                runAfterCommit(() -> rfiCcNotificationService.notifyUsername(notifyTo, null, title, text));
            }
        } catch (Exception e) {
            log.warn("Could not notify the sender of RFI {} about a reply: {}", rfi.getRfiNumber(), e.getMessage());
        }

        // roleOfEmail only returns TO/CC/VIEWER (never SENDER), so the sender's own
        // outgoing emails in the thread never trigger an automatic status change.
        if (role == null || !role.canModify()) {
            log.info("RFI {}: email ignored for status - sender is not on the To/CC list",
                    rfi.getRfiNumber());
            return;
        }

        LocalDateTime replyTimestamp = msg.getMessageDate() != null ? msg.getMessageDate() : LocalDateTime.now();
        if (rfi.getResponseReceivedAt() == null || replyTimestamp.isAfter(rfi.getResponseReceivedAt())) {
            rfi.setResponseReceivedAt(replyTimestamp);
        }

        if (AUTO_TRANSITION_BLOCKED.contains(rfi.getStatus())) {
            return;
        }

        boolean explicitResubmit = RfiStatusPolicy.isExplicitResubmit(msg.getSubject(), msg.getBodyText());
        RfiStatus target = RfiStatusPolicy.statusForReply(role, explicitResubmit);

        rfiStatusTransitionService.transition(
                rfi, target, senderEmail, role, RfiActionSource.EMAIL,
                "Status set automatically from an email sent by a " + role + " participant ("
                        + senderEmail + ")" + (explicitResubmit ? ", marked as a resubmission" : "")
                        + ". The RFI was NOT closed automatically.",
                false);
    }

    /** Runs the task once the surrounding transaction has committed (or right away if there is none). */
    private static void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        task.run();
                    } catch (Exception e) {
                        log.warn("RFI notification after commit failed: {}", e.getMessage());
                    }
                }
            });
        } else {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("RFI notification failed: {}", e.getMessage());
            }
        }
    }
}