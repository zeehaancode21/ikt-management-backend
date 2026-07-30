package com.example.backend.service;

import com.google.firebase.messaging.*;
import com.example.backend.entity.FcmToken;
import com.example.backend.repository.FcmTokenRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class FcmService {

    private final FcmTokenRepository fcmTokenRepository;
    private final FcmTokenCleaner fcmTokenCleaner; 

    public FcmService(FcmTokenRepository fcmTokenRepository, FcmTokenCleaner fcmTokenCleaner) {
        this.fcmTokenRepository = fcmTokenRepository;
        this.fcmTokenCleaner = fcmTokenCleaner; 
    }

    // ─── Save FCM Token ───────────────────────────────────────────────────────

    @Transactional
    public void saveToken(String username, String token) {
        // Check if token already exists for this user
        Optional<FcmToken> existingToken = fcmTokenRepository.findByUsernameAndToken(username, token);
        
        if (existingToken.isPresent()) {
            // Update existing token's updatedAt timestamp
            FcmToken fcmToken = existingToken.get();
            fcmToken.setUpdatedAt(LocalDateTime.now());
            fcmTokenRepository.save(fcmToken);
            return;
        }

        // Create new token
        FcmToken fcmToken = new FcmToken();
        fcmToken.setUsername(username);
        fcmToken.setToken(token);
        // createdAt and updatedAt will be set by @PrePersist
        fcmTokenRepository.save(fcmToken);
         }

    // ─── Delete Token on Logout ───────────────────────────────────────────────

    @Transactional
    public void deleteToken(String token) {
        fcmTokenRepository.deleteByToken(token);
    }

    @Transactional
    public void deleteAllTokensForUser(String username) {
        fcmTokenRepository.deleteByUsername(username);
    }

    // ─── Get All Tokens for a User ──────────────────────────────────────────

    public List<FcmToken> getTokensForUser(String username) {
        return fcmTokenRepository.findByUsername(username);
    }

    // ─── Send Notification to a User (all their devices) ─────────────────────
    // @Async: this is called synchronously from MessageController/GroupController
    // right before they return their HTTP response. Without @Async, the request
    // blocks on a real network call to Firebase for every device/member. Spring
    // proxies this call onto a background thread instead (requires @EnableAsync
    // on your @SpringBootApplication class — see notes).
    //
    // ✅ senderUsername = the person who AUTHORED the message (goes into data.sender
    //    so the receiving client can tell it's not their own message).
    // receiverUsername = whose devices we're delivering to (used to look up tokens).
    @Async
    public void sendNotificationToUser(String receiverUsername, String senderUsername, String title, String body, Long messageId) {
        List<FcmToken> tokens = fcmTokenRepository.findByUsername(receiverUsername);

        if (tokens.isEmpty()) {
            return;
        }
 
        for (FcmToken fcmToken : tokens) {
            sendToToken(fcmToken.getToken(), title, body, receiverUsername, senderUsername, messageId);
        }
    }

    // ─── Send Notification with Data Payload ─────────────────────────────────

    @Async
    public void sendDataNotificationToUser(String receiverUsername, String senderUsername, String title, String body, String type) {
        List<FcmToken> tokens = fcmTokenRepository.findByUsername(receiverUsername);

        if (tokens.isEmpty()) {
            return;
        }

        for (FcmToken fcmToken : tokens) {
            sendDataToToken(fcmToken.getToken(), title, body, type, receiverUsername, senderUsername);
        }
    }

    // ─── Send to a Single Token (with Notification) ───────────────────────────
    // username        = the device owner / receiver (used only for logging here)
    // senderUsername  = the actual author of the message, sent in data.sender
    private void sendToToken(String token, String title, String body, String username, String senderUsername, Long messageId) {
        try {
            Message message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .setImage("https://ik-tangience.web.app/IKT.png")
                        .build()
                )
                // Extra data payload (available in service worker)
                .putData("type", "MESSAGE")
                .putData("title", title)
                .putData("body", body)
                // ✅ FIX: this must be the message AUTHOR, not the receiving device's owner.
                // Falls back to `username` only for system/broadcast sends where there's no real sender.
                .putData("sender", senderUsername != null ? senderUsername : username)
                .putData("timestamp", String.valueOf(System.currentTimeMillis()))
                .putData("messageId", messageId != null ? String.valueOf(messageId) : String.valueOf(System.currentTimeMillis()))
                // Android specific config
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(
                            AndroidNotification.builder()
                                .setIcon("ic_notification")
                                .setColor("#4f46e5")
                                .setSound("default")
                                .setClickAction("OPEN_MESSAGE")
                                .build()
                        )
                        .build()
                )
                // Apple (iOS) specific config
                .setApnsConfig(
                    ApnsConfig.builder()
                        .setAps(
                            Aps.builder()
                                .setSound("default")
                                .setBadge(1)
                                .setContentAvailable(true)
                                .build()
                        )
                        .build()
                )
                // WebPush config
                // WebPush config — NO notification block; let the service worker handle display
// so there's no double-show conflict between FCM auto-display and your SW
.setWebpushConfig(
    WebpushConfig.builder()
        .putData("title", title)
        .putData("body", body)
        .putData("messageId", messageId != null ? String.valueOf(messageId) : String.valueOf(System.currentTimeMillis()))
        .putHeader("TTL", "86400")
        .putHeader("Urgency", "high")
        .build()
)
                       
                .build();

            String response = FirebaseMessaging.getInstance().send(message);
            
        } catch (FirebaseMessagingException e) {
            // Token is invalid/expired — remove it from DB
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED ||
                e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                fcmTokenCleaner.removeInvalidToken(token);
            } else { }
        }
    }
    // ─── Send Data-Only Message (for background handling) ────────────────────
    // username        = the device owner / receiver (used only for logging here)
    // senderUsername  = the actual author of the message, sent in data.sender
    private void sendDataToToken(String token, String title, String body, String type, String username, String senderUsername) {
        try {
            Message message = Message.builder()
                .setToken(token)
                // DATA-ONLY payload — no .setNotification(...) anywhere, so the
                // browser/SW never auto-displays. Display is handled exactly once,
                // either by onBackgroundMessage (tab not focused) or onMessage (tab focused).
                .putData("type", "MESSAGE")
                .putData("title", title)
                .putData("body", body)
                .putData("image", "https://ik-tangience.web.app/IKT.png")
                // ✅ author of the message, not the receiving device's owner.
                .putData("sender", senderUsername != null ? senderUsername : username)
                .putData("timestamp", String.valueOf(System.currentTimeMillis()))
                // Android specific config
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                // Apple (iOS) specific config
                .setApnsConfig(
                    ApnsConfig.builder()
                        .setAps(
                            Aps.builder()
                                .setSound("default")
                                .setBadge(1)
                                .setContentAvailable(true)
                                .build()
                        )
                        .build()
                )
                // WebPush config — TTL only, no notification block here either
                .setWebpushConfig(
    WebpushConfig.builder()
        .putHeader("TTL", "86400")
        .putHeader("Urgency", "high")
        .build()
)
                .build();

            String response = FirebaseMessaging.getInstance().send(message);
           
        } catch (FirebaseMessagingException e) {
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED ||
                e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                fcmTokenCleaner.removeInvalidToken(token);
            } else {
                System.err.println("❌ FCM send error: " + e.getMessage());
            }
        }
    }

    // ─── Send Notification to Multiple Users ─────────────────────────────────
    // No single "sender" makes sense for an arbitrary multi-user broadcast list,
    // so this keeps using the receiver's own name as a fallback (matches old behavior).
    // If you call this for chat-like fan-out where there IS a real sender, prefer
    // calling sendNotificationToUser per-recipient instead so sender is set correctly.
    @Async
    public void sendNotificationToMultipleUsers(List<String> usernames, String title, String body) {
        for (String username : usernames) {
            sendToAllTokensSync(username, title, body, null);
        }
    }

    // ─── Broadcast to All Users ──────────────────────────────────────────────

    @Async
    public void broadcastNotification(String title, String body) {
        List<FcmToken> allTokens = fcmTokenRepository.findAll();
        
        if (allTokens.isEmpty()) {
            return;
        }

        
        for (FcmToken fcmToken : allTokens) {
            // "SYSTEM" as both username and sender — there's no human author for a broadcast.
            sendToToken(fcmToken.getToken(), title, body, "SYSTEM", "SYSTEM",null);
        }
    }

    // ─── Internal helper: send to every device for one user, synchronously ───
    // (used by sendNotificationToMultipleUsers, which is itself already @Async —
    // calling the public @Async sendNotificationToUser from here would just be
    // a same-class self-call that Spring's proxy can't intercept anyway, so we
    // do the real work directly instead.)
    private void sendToAllTokensSync(String username, String title, String body, String senderUsername) {
        List<FcmToken> tokens = fcmTokenRepository.findByUsername(username);
        if (tokens.isEmpty()) {
            return;
        }
        for (FcmToken fcmToken : tokens) {
            sendToToken(fcmToken.getToken(), title, body, username, senderUsername,null);
        }
    }
}