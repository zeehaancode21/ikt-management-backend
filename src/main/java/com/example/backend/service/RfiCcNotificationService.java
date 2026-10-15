package com.example.backend.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.example.backend.entity.Notification;
import com.example.backend.entity.User;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * RfiCcNotificationService.java
 * ---------------------------------------------------------------------
 * Whoever is CC'd on an RFI is "in the loop" on it — this gives them a
 * real in-app/push notification (bell icon + FCM push, same mechanism
 * as leave/permission requests — see PermissionController) for every
 * update on that RFI: created, resubmitted, reopened, manually
 * re-statused, or a reply comes in via Gmail sync. That's in ADDITION
 * to the actual CC'd email, which already goes out separately.
 *
 * Only CC addresses that match a registered employee (User.email) can
 * get one of these — most CCs are external clients/consultants with no
 * account to notify, and they already have the email itself. An
 * employee who is NOT CC'd never gets one of these notifications; they
 * can still see the RFI (and every status change) in the project's RFI
 * list / timeline, they just don't get proactively pushed to — that's
 * the "history, not updates" distinction. Nothing needs to change to
 * keep that true: this service is the only thing that ever creates an
 * RFI_UPDATE notification, and it's only ever called with the RFI's CC
 * list.
 * ---------------------------------------------------------------------
 */
@Service
@Slf4j
public class RfiCcNotificationService {

    /** Notification.type used for every RFI update — see NotificationController#MODULE_TYPES. */
    public static final String NOTIFICATION_TYPE = "RFI_UPDATE";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private FcmService fcmService;

    /**
     * Notifies every CC'd employee of an RFI event. Best-effort and never
     * throws — a failed notification should never undo/fail the RFI
     * action (send, resubmit, status change, sync) that triggered it.
     *
     * @param ccEmails the RFI's current CC list (may be null/empty)
     * @param actingUsername whoever performed the action (skipped so people
     *                       don't get notified about their own action); pass
     *                       null for system-triggered events like Gmail sync
     * @param pushTitle short FCM push title (e.g. "RFI #003 resubmitted")
     * @param content   full notification text, shown in the bell dropdown
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyCcEmployees(List<String> ccEmails, String actingUsername, String pushTitle, String content) {
        if (ccEmails == null || ccEmails.isEmpty()) {
            return;
        }

        // De-dupe case-insensitively so one employee CC'd via two different
        // casings of the same address doesn't get two notifications.
        Set<String> seen = new LinkedHashSet<>();
        for (String rawCc : ccEmails) {
            if (rawCc == null || rawCc.isBlank()) {
                continue;
            }
            String trimmed = rawCc.trim();
            if (!seen.add(trimmed.toLowerCase())) {
                continue;
            }

            try {
                               userRepository.findFirstByEmailIgnoreCase(trimmed).ifPresent(user ->
                        notifyOne(user, actingUsername, pushTitle, content));
            } catch (Exception e) {
                log.warn("Failed to notify CC'd employee for RFI update ({}): {}", trimmed, e.getMessage());
            }
        }
    }

    /**
     * Notifies ONE specific user by username (used for the person who SENT the RFI,
     * who is usually not on its To/CC list). Best-effort, never throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyUsername(String username, String actingUsername, String pushTitle, String content) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            userRepository.findByUsername(username.trim()).ifPresent(user ->
                    notifyOne(user, actingUsername, pushTitle, content));
        } catch (Exception e) {
            log.warn("Failed to notify RFI sender {}: {}", username, e.getMessage());
        }
    }

    private void notifyOne(User user, String actingUsername, String pushTitle, String content) {
        if (actingUsername != null && actingUsername.equalsIgnoreCase(user.getUsername())) {
            return; // don't notify someone about their own action
        }

        Notification notif = new Notification();
        notif.setTargetUsername(user.getUsername());
        notif.setSenderUsername(actingUsername);
        notif.setContent(content);
        notif.setType(NOTIFICATION_TYPE);
        Notification saved = notificationRepository.save(notif);

        try {
            messagingTemplate.convertAndSendToUser(user.getUsername(), "/queue/notifications", saved);
        } catch (Exception e) {
            log.warn("Failed to push RFI notification over websocket to {}: {}", user.getUsername(), e.getMessage());
        }
        try {
            fcmService.sendNotificationToUser(user.getUsername(), actingUsername, pushTitle, content, null);
        } catch (Exception e) {
            log.warn("Failed to send FCM RFI notification to {}: {}", user.getUsername(), e.getMessage());
        }
    }
}