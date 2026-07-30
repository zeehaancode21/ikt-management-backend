package com.example.backend.controller;

import com.example.backend.entity.Attachment;
import com.example.backend.entity.Group;
import com.example.backend.service.FcmService;
import com.example.backend.entity.GroupMessage;
import com.example.backend.repository.AttachmentRepository;
import com.example.backend.repository.GroupMessageRepository;
import com.example.backend.repository.GroupReadStatusRepository;
import com.example.backend.repository.GroupRepository;
import com.example.backend.entity.GroupReadStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
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
public class GroupController {

    private final GroupRepository groupRepo;
    private final GroupMessageRepository groupMsgRepo;
    private final GroupReadStatusRepository groupReadStatusRepo;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final AttachmentRepository attachmentRepository;
    private final FcmService fcmService; // Added via @RequiredArgsConstructor

    // ── Auth helper ──────────────────────────────────────────────────────────
    private String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ── GET /groups → list all groups for current user ───────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Group>> listGroups() {
        String me = currentUser();
        List<Group> groups = groupRepo.findGroupsForUser(me);

        for (Group group : groups) {
            long lastReadId = groupReadStatusRepo.findByGroupIdAndUsername(group.getId(), me)
                    .map(GroupReadStatus::getLastReadMessageId)
                    .orElse(0L);
            long unread = groupMsgRepo.countByGroup_IdAndIdGreaterThanAndSenderUsernameNot(group.getId(), lastReadId, me);
            group.setUnreadCount(unread);
        }

        return ResponseEntity.ok(groups);
    }

    // ── POST /groups/{id}/read → mark a group's messages as read for me ──────
    // Exposed separately too, in case the client wants to mark read without
    // re-fetching the whole message list (e.g. after receiving a live
    // WebSocket message while the chat is already open).
    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<?> markGroupRead(@PathVariable Long id) {
        String me = currentUser();

        return groupRepo.findById(id).map(group -> {
            if (!group.hasMember(me) && !group.getCreatedBy().equals(me)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not a member of this group."));
            }

            long lastReadMessageId = markGroupReadInternal(id, me);
            return ResponseEntity.ok(Map.of("groupId", id, "lastReadMessageId", lastReadMessageId));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Helper: upsert the read marker for (group, user) to the newest message id ─
    private long markGroupReadInternal(Long groupId, String username) {
        Long maxId = groupMsgRepo.findMaxIdByGroupId(groupId);
        long latest = maxId != null ? maxId : 0L;

        GroupReadStatus status = groupReadStatusRepo.findByGroupIdAndUsername(groupId, username)
                .orElseGet(() -> {
                    GroupReadStatus s = new GroupReadStatus();
                    s.setGroupId(groupId);
                    s.setUsername(username);
                    return s;
                });

        // Never move the marker backwards.
        if (latest > status.getLastReadMessageId()) {
            status.setLastReadMessageId(latest);
        }
        status.setLastReadAt(java.time.LocalDateTime.now());
        groupReadStatusRepo.save(status);

        return status.getLastReadMessageId();
    }

    // ── POST /groups → create group ──────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String desc = (String) body.getOrDefault("description", "");

        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) body.getOrDefault("members", new ArrayList<>());

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required."));
        }

        String creator = currentUser();

        // Ensure creator is in the member list
        if (!members.contains(creator)) {
            members = new ArrayList<>(members);
            members.add(0, creator);
        }

        Group group = new Group();
        group.setName(name.trim());
        group.setDescription(desc);
        group.setCreatedBy(creator);
        group.setMemberList(members);

        return ResponseEntity.ok(groupRepo.save(group));
    }

    // ── PUT /groups/{id} → update name / description / members ───────────────
    @PutMapping("/{id}")
    public ResponseEntity<?> updateGroup(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return groupRepo.findById(id).map(group -> {
            if (!group.getCreatedBy().equals(currentUser())) {
                return ResponseEntity.status(403).body(Map.of("error", "Only the creator can edit this group."));
            }
            if (body.containsKey("name") && !((String) body.get("name")).isBlank()) {
                group.setName(((String) body.get("name")).trim());
            }
            if (body.containsKey("description")) {
                group.setDescription((String) body.get("description"));
            }
            if (body.containsKey("members")) {
                @SuppressWarnings("unchecked")
                List<String> members = (List<String>) body.get("members");
                // Always keep creator in list
                if (!members.contains(group.getCreatedBy())) {
                    members = new ArrayList<>(members);
                    members.add(0, group.getCreatedBy());
                }
                group.setMemberList(members);
            }
            return ResponseEntity.ok(groupRepo.save(group));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /groups/{id} → delete group ───────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup(@PathVariable Long id) {
        return groupRepo.findById(id).map(group -> {
            if (!group.getCreatedBy().equals(currentUser())) {
                return ResponseEntity.status(403).body(Map.of("error", "Only the creator can delete this group."));
            }
            groupMsgRepo.deleteAllByGroupId(id);
            groupRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("message", "Group deleted."));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── GET /groups/{id}/messages → fetch message history ────────────────────
    // Also marks the group as read for the requester, mirroring how
    // GET /messages/conversation/{user} marks a DM thread read on open.
    @GetMapping("/{id}/messages")
    @Transactional
    public ResponseEntity<?> getMessages(@PathVariable Long id) {
        String me = currentUser();
        return groupRepo.findById(id).map(group -> {
            if (!group.hasMember(me) && !group.getCreatedBy().equals(me)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not a member of this group."));
            }
            List<GroupMessage> msgs = groupMsgRepo.findByGroup_IdOrderBySentAtAsc(id);
            // Populate transient groupId
            msgs.forEach(m -> {
                try { m.postLoad(); } catch (Exception ignored) {}
            });

            markGroupReadInternal(id, me);

            return ResponseEntity.ok(msgs);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /groups/{id}/messages → send a text message with attachments ─────
    @PostMapping("/{id}/messages")
    @Transactional
    public ResponseEntity<?> sendMessage(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        
        String content = (String) body.getOrDefault("content", "");
        List<Integer> attachmentIds = (List<Integer>) body.get("attachments");
        String messageType = (String) body.getOrDefault("messageType", "MESSAGE");

        // replyToId arrives as a JSON number, which Jackson deserializes into
        // Map<String,Object> as Integer/Long depending on size — normalize via
        // String to avoid a ClassCastException either way.
        Object replyToIdRaw = body.get("replyToId");
        Long replyToId = (replyToIdRaw != null && !"0".equals(String.valueOf(replyToIdRaw)))
                ? Long.valueOf(String.valueOf(replyToIdRaw))
                : null;

        return groupRepo.findById(id).map(group -> {
            String sender = currentUser();
            if (!group.hasMember(sender) && !group.getCreatedBy().equals(sender)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not a member of this group."));
            }

            GroupMessage msg = new GroupMessage();
            msg.setGroup(group);
            msg.setSenderUsername(sender);
            msg.setContent(content);
            msg.setMessageType(messageType);

            // Look up the original message once — used both to set the FK and
            // to populate the denormalized snapshot fields below, so the
            // response already has the quote, no reload needed.
            GroupMessage replyToMsg = replyToId != null ? groupMsgRepo.findById(replyToId).orElse(null) : null;
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
            
            GroupMessage saved = groupMsgRepo.save(msg);
            saved.postLoad();

            // @PostLoad doesn't fire for a freshly-inserted entity, so
            // populate the transient reply-snapshot fields by hand here too.
            if (replyToMsg != null) {
                saved.setReplyToSender(replyToMsg.getSenderUsername());
                saved.setReplyToContent(replyToMsg.getContent());
                saved.setReplyToHasAttachment(replyToMsg.getAttachments() != null && !replyToMsg.getAttachments().isEmpty());
            }

            broadcastToGroup(group, saved);
            
            // ─── FCM Push Notifications ───────────────────────────────────────
            // FcmService.sendNotificationToUser() is now @Async, so this loop
            // schedules all the pushes and returns immediately instead of
            // blocking the HTTP response on every member's network round trip.
            try {
                String groupName = group.getName();
                String senderUsername = sender;
                String notificationTitle = groupName + " • " + senderUsername;
                
                // Prepare notification body
                String notificationBody;
                if (content != null && !content.isEmpty()) {
                    notificationBody = content.length() > 100 
                        ? content.substring(0, 100) + "..." 
                        : content;
                } else if (attachmentIds != null && !attachmentIds.isEmpty()) {
                    notificationBody = "📎 " + attachmentIds.size() + " attachment(s)";
                } else {
                    notificationBody = "New message";
                }
                
                // Get all members and send notifications (except sender)
                List<String> members = group.getMemberList();
                 for (String memberUsername : members) {
                    if (!memberUsername.equals(senderUsername)) {
                        fcmService.sendNotificationToUser(
                            memberUsername,
                            senderUsername,
                            notificationTitle,
                            notificationBody,
                            saved.getId()
                        );
                    }
                }
            } catch (Exception e) {
                // Log error but don't fail message delivery
                System.err.println("Failed to send FCM notifications: " + e.getMessage());
            }
            // ─── End FCM Push Notifications ───────────────────────────────────
            
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /groups/{id}/polls → create a poll ───────────────────────────────
    @PostMapping("/{id}/polls")
    @Transactional
    public ResponseEntity<?> createPoll(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");

        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) body.get("options");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Poll question is required."));
        }
        if (options == null || options.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least 2 options are required."));
        }

        return groupRepo.findById(id).map(group -> {
            String sender = currentUser();
            if (!group.hasMember(sender) && !group.getCreatedBy().equals(sender)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not a member of this group."));
            }

            // Build poll data JSON: { question, options, votes: { optionA: [], optionB: [] } }
            Map<String, Object> pollData = new LinkedHashMap<>();
            pollData.put("question", question);
            pollData.put("options", options);
            Map<String, List<String>> votes = new LinkedHashMap<>();
            for (String opt : options) votes.put(opt, new ArrayList<>());
            pollData.put("votes", votes);

            String pollJson;
            try {
                pollJson = objectMapper.writeValueAsString(pollData);
            } catch (JsonProcessingException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize poll."));
            }

            GroupMessage msg = new GroupMessage();
            msg.setGroup(group);
            msg.setSenderUsername(sender);
            msg.setContent("\uD83D\uDCCA Poll: " + question); // 📊 emoji
            msg.setMessageType("POLL");
            msg.setPollData(pollJson);
            GroupMessage saved = groupMsgRepo.save(msg);
            saved.postLoad();

            broadcastToGroup(group, saved);

            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /groups/{id}/polls/{msgId}/vote → cast a vote ───────────────────
    @PostMapping("/{id}/polls/{msgId}/vote")
    @Transactional
    public ResponseEntity<?> vote(
            @PathVariable Long id,
            @PathVariable Long msgId,
            @RequestBody Map<String, String> body) {
        String option = body.get("option");
        if (option == null || option.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Option is required."));
        }

        return groupMsgRepo.findById(msgId).map(msg -> {
            if (!msg.getGroup().getId().equals(id)) {
                return ResponseEntity.notFound().build();
            }
            if (!"POLL".equals(msg.getMessageType())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Message is not a poll."));
            }

            String voter = currentUser();

            try {
                Map<String, Object> pollData = objectMapper.readValue(
                    msg.getPollData(), new TypeReference<>() {});

                @SuppressWarnings("unchecked")
                Map<String, List<String>> votes =
                    (Map<String, List<String>>) pollData.get("votes");

                if (!votes.containsKey(option)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid poll option."));
                }

                // Remove previous vote from any option (one vote per person)
                votes.values().forEach(voters -> voters.remove(voter));

                // Cast new vote
                votes.get(option).add(voter);

                msg.setPollData(objectMapper.writeValueAsString(pollData));
                GroupMessage saved = groupMsgRepo.save(msg);
                saved.postLoad();

                broadcastToGroup(msg.getGroup(), saved);

                return ResponseEntity.ok(saved);
            } catch (JsonProcessingException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Failed to process vote."));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /groups/{id}/messages/{msgId}/react → toggle an emoji reaction ──
    // One active emoji per person per message, mirroring WhatsApp behaviour.
    @PostMapping("/{id}/messages/{msgId}/react")
    @Transactional
    public ResponseEntity<?> react(
            @PathVariable Long id,
            @PathVariable Long msgId,
            @RequestBody Map<String, String> body) {
        String emoji = body.get("emoji");
        if (emoji == null || emoji.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Emoji is required."));
        }

        return groupMsgRepo.findById(msgId).map(msg -> {
            if (!msg.getGroup().getId().equals(id)) {
                return ResponseEntity.notFound().build();
            }
            String me = currentUser();
            Group group = msg.getGroup();
            if (!group.hasMember(me) && !group.getCreatedBy().equals(me)) {
                return ResponseEntity.status(403).body(Map.of("error", "Not a member of this group."));
            }

            try {
                Map<String, List<String>> reactions = msg.getReactions() == null || msg.getReactions().isBlank()
                        ? new LinkedHashMap<>()
                        : objectMapper.readValue(msg.getReactions(), new TypeReference<Map<String, List<String>>>() {});

                boolean hadThisEmoji = reactions.getOrDefault(emoji, List.of()).contains(me);
                reactions.values().forEach(users -> users.remove(me));
                if (!hadThisEmoji) {
                    reactions.computeIfAbsent(emoji, k -> new ArrayList<>()).add(me);
                }
                reactions.entrySet().removeIf(e -> e.getValue().isEmpty());

                msg.setReactions(objectMapper.writeValueAsString(reactions));
                GroupMessage saved = groupMsgRepo.save(msg);
                saved.postLoad();

                broadcastToGroup(group, saved);

                return ResponseEntity.ok(saved);
            } catch (JsonProcessingException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "Failed to process reaction."));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Helper: push a message to every group member via WebSocket ────────────
    private void broadcastToGroup(Group group, GroupMessage msg) {
        List<String> members = group.getMemberList();
        for (String member : members) {
            messagingTemplate.convertAndSendToUser(
                member.trim(),
                "/queue/group-messages",
                msg
            );
        }
    }
}