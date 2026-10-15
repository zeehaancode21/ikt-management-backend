package com.example.backend.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiActionSource;
import com.example.backend.entity.RfiParticipantRole;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiStatusHistory;
import com.example.backend.repository.RfiRepository;
import com.example.backend.repository.RfiStatusHistoryRepository;

/**
 * THE one place where an RFI's status is changed.
 *
 * The status lives in ONE column (rfis.status), so changing it here is
 * automatically "global": every participant, on the web or the mobile app,
 * reads the same value the next time they load or refresh the RFI.
 *
 * Every path goes through here:
 *   - manual status change in the app        (RfiServiceImpl#updateStatus)
 *   - reply / resubmit / reopen in the app   (RfiServiceImpl#sendFollowUpAndTransition)
 *   - inbound email                          (RfiGmailSyncService)
 *
 * FIXES in this version:
 *   1. The person who SENT the RFI (usually NOT on the To/CC list) is now also
 *      notified when someone changes the status in the app. Before, only To/CC
 *      people were notified, so the sender's screen never refreshed.
 *   2. Notifications (websocket + push) are sent AFTER the database commit.
 *      Before, the websocket push could reach the browser before the new status
 *      was committed, so the quick reload still read the OLD status.
 *
 * Runs inside the caller's transaction.
 */
@Service
public class RfiStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(RfiStatusTransitionService.class);

    @Autowired
    private RfiRepository rfiRepository;

    @Autowired
    private RfiStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private RfiAccessService rfiAccessService;

    @Autowired
    private RfiCcNotificationService notificationService;

    /**
     * @param changedBy         username (in-app) or sender email (inbound email)
     * @param actorRole         TO or CC - who caused the change
     * @param source            APP or EMAIL
     * @param recordIfUnchanged true = still write a history row when the status is
     *                          already the target (used for resubmit/reply so every
     *                          follow-up shows on the timeline); false = do nothing
     * @return the saved RFI
     */
    public Rfi transition(Rfi rfi, RfiStatus newStatus, String changedBy,
                          RfiParticipantRole actorRole, RfiActionSource source,
                          String note, boolean recordIfUnchanged) {

        RfiStatus oldStatus = rfi.getStatus();
        if (oldStatus == newStatus && !recordIfUnchanged) {
            return rfi;
        }

        rfi.setStatus(newStatus);
        Rfi saved = rfiRepository.save(rfi);

        RfiStatusHistory history = new RfiStatusHistory();
        history.setRfi(saved);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setChangedBy(changedBy);
        history.setNote(note);
        history.setActorRole(actorRole);
        history.setSource(source);
        statusHistoryRepository.save(history);

        // ── Work out who to tell (all DB reads happen NOW, inside the transaction) ──
        final String actingUsername = (source == RfiActionSource.APP) ? changedBy : null;
        final String who = (actorRole != null ? actorRole.name() + " participant " : "") + changedBy;
        final String title = "RFI " + saved.getRfiNumber() + " \u2192 " + newStatus;
        final String content = "RFI " + saved.getRfiNumber() + " (" + saved.getProjectName()
                + ") status changed from " + oldStatus + " to " + newStatus + " by " + who + ".";
        final List<String> participants = rfiAccessService.participantEmails(saved);

        // The sender of the RFI is often NOT on To/CC (the recipient is usually an outside
        // client), so the To/CC notification would never reach them. For in-app changes we
        // notify them here. For inbound email, RfiGmailSyncService already sends the sender
        // a "Reply received" notification, so skip it here to avoid a duplicate.
        String senderTmp = null;
        if (source == RfiActionSource.APP) {
            String sentBy = saved.getSentBy();
            if (sentBy != null && !sentBy.isBlank()
                    && rfiAccessService.roleOfUser(saved, sentBy.trim()) == RfiParticipantRole.SENDER) {
                senderTmp = sentBy.trim();
            }
        }
        final String senderToNotify = senderTmp;

        // ── Send only after the status is really committed ──
        runAfterCommit(() -> {
            notificationService.notifyCcEmployees(participants, actingUsername, title, content);
            if (senderToNotify != null) {
                notificationService.notifyUsername(senderToNotify, actingUsername, title, content);
            }
        });

        return saved;
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