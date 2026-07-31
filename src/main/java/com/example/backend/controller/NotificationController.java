package com.example.backend.controller;
import org.springframework.transaction.annotation.Transactional;
import com.example.backend.entity.Attachment;
import com.example.backend.entity.Notification;
import com.example.backend.repository.AttachmentRepository;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.FcmService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notifications")
// @CrossOrigin(
//     origins = "http://localhost:5173",
//     allowCredentials = "true",
//     allowedHeaders = "*"
// )
public class NotificationController {

    private final NotificationRepository repo;
    private final UserRepository userRepo;
    private final AttachmentRepository attachmentRepository;
    private final FcmService fcmService;

    public NotificationController(NotificationRepository repo, 
                              UserRepository userRepo,
                              AttachmentRepository attachmentRepository,
                              FcmService fcmService) {
    this.repo = repo;
    this.userRepo = userRepo;
    this.attachmentRepository = attachmentRepository;
    this.fcmService = fcmService;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /**
     * Maps the raw Notification.type values used across the app to the
     * sidebar "module" they belong to. Add a new module here (and its
     * types) whenever a new area of the app starts creating notifications
     * that should show a badge in the left nav.
     */
    private static final Map<String, List<String>> MODULE_TYPES = Map.of(
        "messages", List.of("MESSAGE"),
        "leave", List.of("LEAVE_REQUEST", "LEAVE_APPROVED", "LEAVE_REJECTED"),
        "permission", List.of("PERMISSION_REQUEST", "PERMISSION_APPROVED", "PERMISSION_REJECTED")
    );

    /**
     * Unread notification counts grouped by sidebar module (messages, leave,
     * permission, ...). Powers the badges shown next to each item in the
     * left navigation. Any notification type not mapped to a known module
     * (e.g. BROADCAST, which the bell already surfaces) is ignored here.
     */
    @GetMapping("/module-counts")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Long>> getModuleCounts() {
        List<Object[]> rows = repo.countUnreadForUserGroupedByType(currentUsername());

        // type -> count, straight from the DB
        Map<String, Long> byType = new java.util.HashMap<>();
        for (Object[] row : rows) {
            byType.put((String) row[0], (Long) row[1]);
        }

        // Roll the per-type counts up into the module buckets the sidebar
        // cares about.
        Map<String, Long> byModule = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : MODULE_TYPES.entrySet()) {
            long total = entry.getValue().stream()
                .mapToLong(type -> byType.getOrDefault(type, 0L))
                .sum();
            byModule.put(entry.getKey(), total);
        }

        return ResponseEntity.ok(byModule);
    }

    /**
     * Marks all unread notifications belonging to a module as read — called
     * when the user opens that module's page, so its sidebar badge clears.
     */
    @PutMapping("/module/{module}/read")
    public ResponseEntity<Void> markModuleRead(@PathVariable String module) {
        List<String> types = MODULE_TYPES.get(module);
        if (types == null || types.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        repo.markReadForUserAndTypes(currentUsername(), types);
        return ResponseEntity.ok().build();
    }

    /** Get only UNREAD notifications for current user */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Notification>> getMyNotifications() {
        return ResponseEntity.ok(repo.findUnreadForUser(currentUsername()));
    }

    /** Get all notifications (read + unread) for current user — used by Announcements */
    @GetMapping("/all")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Notification>> getAllMyNotifications() {
        return ResponseEntity.ok(repo.findAllForUser(currentUsername()));
    }

    @GetMapping("/announcements")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Notification>> getAnnouncements() {
    return ResponseEntity.ok(repo.findAnnouncementsForUser(currentUsername()));
}

    /** Unread notification count */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        return ResponseEntity.ok(Map.of("count", repo.countUnreadForUser(currentUsername())));
    }

    /** Mark a single notification as read (removes it from the unread list) */
    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        repo.deleteByIdForUser(id, currentUsername());
        return ResponseEntity.ok().build();
    }

    /** Mark ALL notifications as read for current user */
    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        repo.deleteAllAnnouncementsForUser(currentUsername());
        return ResponseEntity.ok().build();
    }

    /**
     * Owner broadcasts an announcement notification.
     * Creates one notification PER USER so that each user can track read/unread independently.
     */
    @PostMapping("/broadcast")
    public ResponseEntity<Void> broadcast(@RequestBody Map<String, Object> body) {
        String content = (String) body.get("content");
        List<Integer> attachmentIds = (List<Integer>) body.get("attachments");
        
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

       // Fetch all users (except the sender/owner)
        List<String> allUsernames = userRepo.findAll()
                .stream()
                .map(u -> u.getUsername())
                .filter(u -> !u.equals(currentUsername()))
                .toList();

        // Was: attachmentRepository.findById() called once PER USER PER ATTACHMENT (N+1,
        // actually N*M queries). Resolve the attachment list ONE time and reuse it below.
        List<Attachment> resolvedAttachments = (attachmentIds == null || attachmentIds.isEmpty())
                ? List.of()
                : attachmentRepository.findAllById(
                        attachmentIds.stream().map(Integer::longValue).toList());

        List<Notification> toSave = new ArrayList<>();
        for (String username : allUsernames) {
            Notification notif = new Notification();
            notif.setTargetUsername(username);
            notif.setContent(content);
            notif.setType("BROADCAST");
            notif.setRead(false);

            // Each Notification needs its own List instance (shared join-table rows,
            // not a shared Java collection reference) even though the Attachment
            // entities themselves are safely reused.
            if (!resolvedAttachments.isEmpty()) {
                notif.setAttachments(new ArrayList<>(resolvedAttachments));
            }

            toSave.add(notif);
        }

        // Also save one for the sender so they can see their own announcement history
        Notification senderCopy = new Notification();
        senderCopy.setTargetUsername(currentUsername());
        senderCopy.setContent(content);
        senderCopy.setType("BROADCAST");
        senderCopy.setRead(true); // already "read" for the sender since they wrote it

        if (!resolvedAttachments.isEmpty()) {
            senderCopy.setAttachments(new ArrayList<>(resolvedAttachments));
        }

        toSave.add(senderCopy);

       repo.saveAll(toSave);

// Send FCM push notification to all users (not the owner)
String fcmBody = content.length() > 100 ? content.substring(0, 100) + "..." : content;
for (String username : allUsernames) {
    fcmService.sendNotificationToUser(
        username,
        currentUsername(),
        "📢 New Announcement",
        fcmBody,
        null
    );
}

return ResponseEntity.ok().build();
    }
}