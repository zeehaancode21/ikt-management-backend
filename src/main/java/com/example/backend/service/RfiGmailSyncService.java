package com.example.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * Matching strategy, in order of preference:
 *   1. Rfi.gmailThreadId (set at send time) — the primary, reliable match.
 *      Every message returned by fetchThreadMessages(threadId) belongs to
 *      that thread by definition, so there's no sender/subject guessing
 *      involved here at all.
 *   2. For legacy RFIs sent before this feature existed (gmailThreadId is
 *      null), a one-time subject search backfills it — see
 *      backfillThreadIdIfMissing. If that search is ambiguous (matches more
 *      than one thread), nothing is guessed: it's skipped (a warning is
 *      logged) and that RFI is left without a thread id.
 *
 * Duplicate prevention: RfiEmailMessage.gmailMessageId has a unique index;
 * this service also checks existsByGmailMessageId before inserting so a
 * message already imported by an earlier sync is simply skipped.
 */
@Service
@Slf4j
public class RfiGmailSyncService {

    // A reply never moves a CLOSED or CANCELLED RFI on its own — the user
    // has to Reopen it first. See the "RFI STATUS CONTROL" requirement.
    private static final Set<RfiStatus> AUTO_TRANSITION_BLOCKED = EnumSet.of(RfiStatus.CLOSED, RfiStatus.CANCELLED);

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

    // Notifies any CC'd employee (in-app + push) when a reply comes in and
    // auto-moves the RFI to RESPONSE_RECEIVED — same as manual status
    // changes in RfiServiceImpl. See its javadoc.
    // Decides whether an email sender is on the RFI's To/CC list.
    @Autowired
    private RfiAccessService rfiAccessService;

    @Autowired
    private RfiCcNotificationService rfiCcNotificationService;

    // The single place that changes an RFI's status + writes history + notifies.
    @Autowired
    private RfiStatusTransitionService rfiStatusTransitionService;

    // Added so a sync authenticates as the ACTING RFI's own connected
    // Gmail account (RfiSettings) — the same one that RFI was originally
    // SENT from (rfi.getSentBy()) — instead of the single legacy global
    // account GmailEmailService otherwise falls back to.
    @Autowired
    private RfiSettingsService rfiSettingsService;

    @Autowired
    private UserRepository userRepository;

    // Each RFI is synced in its OWN short transaction (see inTransaction). Before, syncAllForProject
    // ran every RFI in ONE transaction: a single database hiccup on one RFI marked the whole
    // transaction rollback-only, so at commit EVERY imported reply of the project was thrown away
    // and the button answered 500 "Sync failed" — while the log showed the imports as fine.
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Runs one RFI's sync in its own transaction; a failure only affects that RFI. */
    private RfiSyncResultDto inTransaction(Rfi rfi) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                // Re-read inside the transaction so the RFI is a managed entity, exactly as it
                // was when the whole sync ran in one @Transactional method.
                Rfi managed = rfiRepository.findById(rfi.getId()).orElse(rfi);
                return syncInternal(managed);
            });
        } catch (Exception e) {
            log.warn("Gmail sync of RFI {} (id={}) was rolled back: {}", rfi.getRfiNumber(), rfi.getId(), e.getMessage());
            return new RfiSyncResultDto(rfi.getId(), rfi.getRfiNumber(), false, 0,
                    "Sync could not be saved (" + e.getMessage() + "). Try again.", rfi);
        }
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
     * (rfi.getSentBy()), from THEIR OWN RfiSettings row (RFI Settings
     * screen), never the single legacy global GMAIL_* application.properties
     * values. This is what fixes syncs failing with "No Gmail refresh token
     * available to authenticate with" once a user has their own token/
     * Client ID/Client Secret saved in RFI Settings.
     */
    private SenderCredentials resolveSenderCredentials(String sentBy) throws Exception {
        if (sentBy == null || sentBy.isBlank()) {
            throw new Exception("This RFI has no recorded sender to sync Gmail as");
        }
        // trim() guards against the very common case where the logged-in
        // user's username was saved onto the RFI (or exists in the users
        // table) with leading/trailing whitespace picked up from a form
        // field — findByUsername is an exact match, so "noor" vs "noor "
        // silently fails to find the row and looks identical to "user has
        // no settings" unless you inspect the raw DB value.
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

    /**
     * Google rejects a refresh token with "invalid_grant" when: the token
     * was revoked (user removed the app's access under their Google
     * Account), the token expired from disuse, or — very commonly when
     * this works for one teammate but not another — the OAuth consent
     * screen for that Client ID/Secret is still in "Testing" publish
     * status in Google Cloud Console and this user's Gmail address was
     * never added under "Test users", so Google silently refuses to issue
     * fresh access tokens for them while happily doing so for whichever
     * account WAS added as a test user.
     */
    private boolean looksLikeInvalidGrant(Throwable e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("invalid_grant");
    }

        /**
     * Returns every Gmail thread ID that could plausibly hold this RFI's
     * conversation: the thread captured at send time (if any), PLUS any
     * thread Gmail currently associates with the RFI's unique generated
     * subject.
     *
     * This exists because Gmail's own threading (References/In-Reply-To +
     * subject match) sometimes fails to group a genuine reply into the
     * original thread, silently starting a brand-new thread that
     * fetchThreadMessages(rfi.getGmailThreadId()) alone would never see.
     * Searching by subject as well catches that case, so "Check for
     * Replies" doesn't come back empty when a reply genuinely exists.
     */
    private Set<String> resolveCandidateThreadIds(Rfi rfi, SenderCredentials creds) throws Exception {
        Set<String> threadIds = new LinkedHashSet<>();
        if (rfi.getGmailThreadId() != null && !rfi.getGmailThreadId().isBlank()) {
            threadIds.add(rfi.getGmailThreadId());
        }
        if (rfi.getGeneratedEmailSubject() != null && !rfi.getGeneratedEmailSubject().isBlank()) {
            threadIds.addAll(emailService.searchThreadIdsBySubject(
                    rfi.getGeneratedEmailSubject(), creds.refreshToken, creds.clientId, creds.clientSecret));
        }
        return threadIds;
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
            for (GmailEmailService.MessageRef ref : refs) {
                if (ref.threadId() != null && watchedThreadIds.contains(ref.threadId())
                        && !result.contains(ref.threadId())
                        && !emailMessageRepository.existsByGmailMessageId(ref.messageId())) {
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

    /** Manually triggered sync for a single RFI ("Sync Gmail" button on the RFI detail view). */
    public RfiSyncResultDto syncRfi(Long rfiId) {
        Rfi rfi = rfiRepository.findById(rfiId)
                .orElseThrow(() -> new ResourceNotFoundException("RFI not found with ID: " + rfiId));
        return inTransaction(rfi);
    }

    /** Syncs every RFI in a project in one pass ("Sync all" on the RFI History tab). */
    public List<RfiSyncResultDto> syncAllForProject(String projectName) {
        List<Rfi> rfis = rfiRepository.findByProjectNameOrderByIdAsc(projectName);
        List<RfiSyncResultDto> results = new ArrayList<>();
        for (Rfi rfi : rfis) {
            results.add(inTransaction(rfi));
        }
        return results;
    }

    // ── Internals ────────────────────────────────────────────────────────

        private RfiSyncResultDto syncInternal(Rfi rfi) {
        try {
            // Always the RFI's OWN sender's RFI Settings — never the legacy
            // global GMAIL_* application.properties values.
            SenderCredentials creds = resolveSenderCredentials(rfi.getSentBy());

            backfillThreadIdIfMissing(rfi, creds);

            Set<String> candidateThreadIds = resolveCandidateThreadIds(rfi, creds);
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

            // This RFI's own sender address (from their RFI Settings), not a
            // single application-wide address — each RFI can have a
            // different sender, so direction has to be judged per-RFI.
            String ourFromAddress = creds.fromEmail;
            int newCount = 0;

            for (GmailFetchedMessage msg : fetched) {
                if (msg.getGmailMessageId() == null
                        || emailMessageRepository.existsByGmailMessageId(msg.getGmailMessageId())) {
                    continue; // already imported by an earlier sync
                }

                // The From header is usually "Name <a@b.com>", so compare plain addresses.
                String ourNormalized = RfiAccessService.normalizeEmail(ourFromAddress);
                String msgFromNormalized = RfiAccessService.normalizeEmail(msg.getFromAddress());
                RfiEmailDirection direction =
                        (ourNormalized != null && ourNormalized.equals(msgFromNormalized))
                        ? RfiEmailDirection.OUTGOING
                        : RfiEmailDirection.INCOMING;

                if (direction == RfiEmailDirection.INCOMING
                        && !msg.getGmailThreadId().equals(rfi.getGmailThreadId())) {
                    // The reply landed in a Gmail thread different from the one we
                    // tracked at send time (common when the recipient's mail client
                    // drops/rewrites References or In-Reply-To). Adopt the thread the
                    // actual reply is in so future syncs find it directly.
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
                entity.setSubject(msg.getSubject());
                entity.setBodyText(msg.getBodyText());
                entity.setSnippet(msg.getSnippet());
                entity.setMessageDate(msg.getMessageDate());
                entity.setAttachments(downloadAttachments(msg, rfi.getSentBy(), creds));

                try {
                    emailMessageRepository.save(entity);
                } catch (DataIntegrityViolationException raceLostToAnotherSync) {
                    continue;
                }

                newCount++;

                if (direction == RfiEmailDirection.INCOMING) {
                    applyIncomingReplyEffects(rfi, msg);
                }
            }

            if (newCount > 0) {
                rfiRepository.save(rfi);
            }

            return new RfiSyncResultDto(rfi.getId(), rfi.getRfiNumber(), true, newCount, null, rfi);
        } catch (Exception e) {
            log.warn("Gmail sync failed for RFI {} (id={}, sentBy={}): {}",
                    rfi.getRfiNumber(), rfi.getId(), rfi.getSentBy(), e.getMessage());

            String errorMessage = e.getMessage();
            if (looksLikeInvalidGrant(e)) {
                // Flip THIS user's token to invalid (never a global flag) so
                // the RFI Settings screen can surface "reconnect Gmail" for
                // them specifically, instead of the sync silently retrying
                // the same rejected token on every future attempt.
                try {
                    User user = userRepository.findByUsername(rfi.getSentBy().trim()).orElse(null);
                    if (user != null) {
                        rfiSettingsService.markTokenAsInvalid(user.getId());
                    }
                } catch (Exception markFailure) {
                    log.warn("Could not mark token invalid for RFI {}: {}", rfi.getRfiNumber(), markFailure.getMessage());
                }
                errorMessage = (rfi.getSentBy() != null ? rfi.getSentBy() : "This user") + "'s Gmail authorization "
                        + "was rejected by Google (invalid_grant). This is per-account — it does not affect other "
                        + "users' syncs. Most common causes: (1) the OAuth consent screen for their Client ID is "
                        + "still in \"Testing\" mode in Google Cloud Console and their Gmail address was never "
                        + "added under Test users, (2) they revoked the app's access under Google Account > "
                        + "Security > Third-party access, or (3) the refresh token expired from 6+ months of "
                        + "disuse. They need to go through the Google OAuth consent screen again in RFI Settings "
                        + "to get a fresh token. Original error: " + e.getMessage();
            }

            return new RfiSyncResultDto(rfi.getId(), rfi.getRfiNumber(), false, 0, errorMessage, rfi);
        }
    }
    private List<Attachment> downloadAttachments(GmailFetchedMessage msg, String uploadedBy, SenderCredentials creds) {
        List<Attachment> result = new ArrayList<>();
        for (GmailFetchedAttachment att : msg.getAttachments()) {
            try {
                byte[] bytes = emailService.fetchAttachmentBytes(
                        msg.getGmailMessageId(), att.getGmailAttachmentId(),
                        creds.refreshToken, creds.clientId, creds.clientSecret);
                Attachment saved = fileStorageService.storeBytes(
                        bytes, att.getFilename(), att.getMimeType(),
                        uploadedBy != null ? uploadedBy : "gmail-sync");
                result.add(saved);
            } catch (Exception e) {
                // Don't fail the whole reply just because one attachment couldn't be
                // fetched — the email and any other attachments are still stored.
                log.warn("Failed to download attachment '{}' on Gmail message {}: {}",
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

        // Tell the person who SENT the RFI that a reply arrived. They are usually not on the
        // To/CC list (the recipient is often an outside client), so the status-change
        // notification below would never reach them. If the sender IS on To/CC they are
        // already covered by that notification, so skip them here to avoid a duplicate.
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
        if (!role.canModify()) {
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

    /**
     * For RFIs sent before Gmail thread tracking existed. Searches Gmail for
     * the RFI's exact generated subject; if that matches exactly one
     * thread, adopts it. Anything else (zero or multiple matches) is left
     * alone rather than guessed — an ambiguous result is only logged as a
     * warning and skipped.
     */
    private void backfillThreadIdIfMissing(Rfi rfi, SenderCredentials creds) throws Exception {
        if (rfi.getGmailThreadId() != null && !rfi.getGmailThreadId().isBlank()) {
            return;
        }

        List<String> threadIds = emailService.searchThreadIdsBySubject(
                rfi.getGeneratedEmailSubject(), creds.refreshToken, creds.clientId, creds.clientSecret);
        if (threadIds.size() == 1) {
            rfi.setGmailThreadId(threadIds.get(0));
            rfiRepository.save(rfi);
        } else if (threadIds.size() > 1) {
            log.warn("RFI {} ({}): subject search matched {} Gmail threads; not guessing, skipping.",
                    rfi.getRfiNumber(), rfi.getProjectName(), threadIds.size());
        }
        // threadIds.isEmpty(): no reply yet, or nothing found — leave null and try again next sync.
    }
}