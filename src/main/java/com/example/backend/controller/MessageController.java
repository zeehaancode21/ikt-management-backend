package com.example.backend.controller;

import com.example.backend.service.FcmService;
import com.example.backend.entity.Attachment;
import com.example.backend.entity.Message;
import com.example.backend.entity.Notification;
import com.example.backend.repository.AttachmentRepository;
import com.example.backend.repository.MessageRepository;
import com.example.backend.repository.NotificationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/messages")
// @CrossOrigin(
//     origins = "http://localhost:5173",
//     allowCredentials = "true",
//     allowedHeaders = "*",
//     methods = {
//         RequestMethod.GET, RequestMethod.POST,
//         RequestMethod.PUT, RequestMethod.DELETE,
//         RequestMethod.OPTIONS
//     }
// )
public class MessageController {

    private final MessageRepository messageRepo;
    private final NotificationRepository notifRepo;
    private final SimpMessagingTemplate messagingTemplate;
    private final AttachmentRepository attachmentRepository;
    private final FcmService fcmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MessageController(MessageRepository messageRepo,
                             NotificationRepository notifRepo,
                             SimpMessagingTemplate messagingTemplate,
                             AttachmentRepository attachmentRepository,
                             FcmService fcmService) {
        this.messageRepo = messageRepo;
        this.notifRepo = notifRepo;
        this.messagingTemplate = messagingTemplate;
        this.attachmentRepository = attachmentRepository;
        this.fcmService = fcmService;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @PostMapping("/send")
    @Transactional
    public ResponseEntity<Message> send(@RequestBody Map<String, Object> body) {
        String receiver = (String) body.get("receiverUsername");
        String content = (String) body.getOrDefault("content", "");
        List<Integer> attachmentIds = (List<Integer>) body.get("attachments");

        // replyToId arrives as a JSON number, which Jackson deserializes into
        // Map<String,Object> as Integer (or Long depending on size) — normalize
        // via String to avoid a ClassCastException either way.
        Object replyToIdRaw = body.get("replyToId");
        Long replyToId = (replyToIdRaw != null && !"0".equals(String.valueOf(replyToIdRaw)))
                ? Long.valueOf(String.valueOf(replyToIdRaw))
                : null;

        if (receiver == null || receiver.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String senderUsername = currentUsername();
        
        Message msg = new Message();
        msg.setSenderUsername(senderUsername);
        msg.setReceiverUsername(receiver);
        msg.setContent(content);

        // Look up the original message once — used both to set the FK and to
        // populate the denormalized snapshot fields below, so the response
        // sent back to the sender already has the quote, no reload needed.
        Message replyToMsg = replyToId != null ? messageRepo.findById(replyToId).orElse(null) : null;
        if (replyToMsg != null) {
            msg.setReplyToId(replyToMsg.getId());
        }
        
        // Add attachments if present — single batched findAllById() instead of
        // looping findById() once per attachment id.
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            List<Long> ids = attachmentIds.stream().map(Integer::longValue).collect(Collectors.toList());
            Map<Long, Attachment> byId = attachmentRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(Attachment::getId, a -> a));
            List<Attachment> attachments = ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            msg.setAttachments(attachments);
        }
        
        Message saved = messageRepo.save(msg);

        // @PostLoad doesn't fire for a freshly-inserted entity (it's not a
        // "load", it's a save), so populate the transient reply-snapshot
        // fields by hand for this response.
        if (replyToMsg != null) {
            saved.setReplyToSender(replyToMsg.getSenderUsername());
            saved.setReplyToContent(replyToMsg.getContent());
            saved.setReplyToHasAttachment(replyToMsg.getAttachments() != null && !replyToMsg.getAttachments().isEmpty());
        }

        // Create notification content
        String notificationContent = "New message from " + senderUsername;
        if (content != null && !content.isEmpty()) {
            notificationContent += ": " + content;
        } else if (attachmentIds != null && !attachmentIds.isEmpty()) {
            notificationContent += " with " + attachmentIds.size() + " attachment(s)";
        }
        
        Notification notif = new Notification();
        notif.setTargetUsername(receiver);
        notif.setContent(notificationContent);
        notif.setType("MESSAGE");
        Notification savedNotif = notifRepo.save(notif);

        // Send WebSocket notifications
        messagingTemplate.convertAndSendToUser(receiver, "/queue/messages", saved);
        messagingTemplate.convertAndSendToUser(receiver, "/queue/notifications", savedNotif);

        // FCM Push Notification
        String fcmNotificationBody;
        if (content != null && !content.isEmpty()) {
            fcmNotificationBody = content.length() > 100 
                ? content.substring(0, 100) + "..." 
                : content;
        } else if (attachmentIds != null && !attachmentIds.isEmpty()) {
            fcmNotificationBody = "📎 " + attachmentIds.size() + " attachment(s)";
        } else {
            fcmNotificationBody = "New message";
        }
        
        // Send FCM push notification to receiver with error handling.
        // FcmService.sendNotificationToUser() is @Async, so this returns
        // immediately instead of blocking on the network call to Firebase.
        try {
            String fcmNotificationTitle = senderUsername;
            fcmService.sendNotificationToUser(
                receiver,
                senderUsername,
                fcmNotificationTitle,
                fcmNotificationBody,
                saved.getId()
            );
        } catch (Exception e) {
            System.err.println("Failed to send FCM notification: " + e.getMessage());
        }

        return ResponseEntity.ok(saved);
    }

    
    @GetMapping("/conversation/{otherUser}")
@Transactional
public ResponseEntity<Page<Message>> getConversation(
        @PathVariable String otherUser,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {

    String me = currentUsername();
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));

    messageRepo.markAsRead(me, otherUser);

    Page<Message> messages = messageRepo.findConversationPage(me, otherUser, pageable);
    return ResponseEntity.ok(messages);
}

    @GetMapping("/inbox")
    @Transactional(readOnly = true)
    public ResponseEntity<Page<Message>> getInbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        String username = currentUsername();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"));
        Page<Message> messages = messageRepo.findAllInvolving(username, pageable);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/unread-count")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        long count = messageRepo
                .findByReceiverUsernameAndReadByReceiverFalse(currentUsername())
                .size();
        return ResponseEntity.ok(Map.of("count", count));
    }

    // ── DELETE /messages/{id} → delete a single message (sender only, for everyone) ─
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteMessage(@PathVariable Long id) {
        String me = currentUsername();

        return messageRepo.findById(id).map(msg -> {
            if (!msg.getSenderUsername().equals(me)) {
                return ResponseEntity.status(403).body(Map.of("error", "You can only delete messages you sent."));
            }

            String otherUser = msg.getReceiverUsername();

            // Delete the entity itself (not a bulk JPQL delete) so Hibernate
            // correctly removes the corresponding message_attachments join
            // rows before removing the message row.
            messageRepo.delete(msg);

            Map<String, Object> payload = Map.of("id", id, "otherUser", otherUser);
            messagingTemplate.convertAndSendToUser(otherUser, "/queue/messages-deleted", payload);
            messagingTemplate.convertAndSendToUser(me, "/queue/messages-deleted", payload);

            return ResponseEntity.ok(Map.of("deleted", true, "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /messages/conversation/{otherUser} → delete an entire DM thread ────
    @DeleteMapping("/conversation/{otherUser}")
    @Transactional
    public ResponseEntity<?> deleteConversation(@PathVariable String otherUser) {
        String me = currentUsername();

        // Reuses the fetch-joined finder (loads attachments) and deletes each
        // entity via deleteAll() rather than a bulk JPQL DELETE, so the
        // message_attachments join rows are cleaned up correctly for every
        // message instead of risking an FK violation.
        List<Message> messages = messageRepo.findConversation(me, otherUser);
        if (messages.isEmpty()) {
            return ResponseEntity.ok(Map.of("deleted", true, "count", 0));
        }

        messageRepo.deleteAll(messages);

        messagingTemplate.convertAndSendToUser(otherUser, "/queue/conversation-deleted", Map.of("otherUser", me));
        messagingTemplate.convertAndSendToUser(me, "/queue/conversation-deleted", Map.of("otherUser", otherUser));

        return ResponseEntity.ok(Map.of("deleted", true, "count", messages.size()));
    }

    // ── POST /messages/{id}/react → toggle an emoji reaction on a DM ─────────
    // A user can only have one active emoji per message (tapping the same
    // emoji again removes it, tapping a different one switches it) — mirrors
    // WhatsApp's single-reaction-per-person behaviour.
    @PostMapping("/{id}/react")
    @Transactional
    public ResponseEntity<?> react(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String emoji = body.get("emoji");
        if (emoji == null || emoji.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Emoji is required."));
        }

        return messageRepo.findById(id).map(msg -> {
            String me = currentUsername();
            if (!msg.getSenderUsername().equals(me) && !msg.getReceiverUsername().equals(me)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not part of this conversation."));
            }

            Map<String, List<String>> reactions = parseReactions(msg.getReactions());

            boolean hadThisEmoji = reactions.getOrDefault(emoji, List.of()).contains(me);
            // Remove this user from every emoji first (one reaction per person).
            reactions.values().forEach(users -> users.remove(me));
            if (!hadThisEmoji) {
                reactions.computeIfAbsent(emoji, k -> new java.util.ArrayList<>()).add(me);
            }
            reactions.entrySet().removeIf(e -> e.getValue().isEmpty());

            msg.setReactions(writeReactions(reactions));
            Message saved = messageRepo.save(msg);

            messagingTemplate.convertAndSendToUser(saved.getSenderUsername(), "/queue/messages", saved);
            messagingTemplate.convertAndSendToUser(saved.getReceiverUsername(), "/queue/messages", saved);

            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, List<String>> parseReactions(String json) {
        if (json == null || json.isBlank()) return new java.util.LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    private String writeReactions(Map<String, List<String>> reactions) {
        try {
            return objectMapper.writeValueAsString(reactions);
        } catch (Exception e) {
            return null;
        }
    }
}