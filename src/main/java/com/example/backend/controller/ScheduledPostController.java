package com.example.backend.controller;

import com.example.backend.entity.ApiResponse;
import com.example.backend.entity.RecurrenceType;
import com.example.backend.entity.ScheduledPost;
import com.example.backend.service.ScheduledPostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Scheduling layer for the existing AI Posting / Manual Prompting feature.
 * Nothing here touches SocialHubController, MistralService, or
 * LinkedInService's existing behavior — this only lets the user queue up a
 * future call into that same workflow (see ScheduledPostService), manage
 * those jobs, and see their outcome/history.
 */
@RestController
@RequestMapping("/social-post/schedule")
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:8080"}, allowCredentials = "true")
public class ScheduledPostController {

    @Autowired
    private ScheduledPostService scheduledPostService;

    private String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // GET /social-post/schedule -> every scheduled post (pending + history) for the caller
    @GetMapping
    public ResponseEntity<ApiResponse<List<ScheduledPost>>> list() {
        return ResponseEntity.ok(ApiResponse.success(scheduledPostService.listForUser(currentUser())));
    }

    // GET /social-post/schedule/{id}
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduledPost>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(scheduledPostService.getOwned(id, currentUser())));
    }

    // POST /social-post/schedule
    // body: { categoryId?: string, customPrompt?: string, scheduledFor: ISO datetime string,
    //          recurrence?: "NONE" | "WEEKLY" | "MONTHLY" }
    @PostMapping
    public ResponseEntity<ApiResponse<ScheduledPost>> create(@RequestBody Map<String, String> body) {
        ScheduledPost saved = scheduledPostService.create(
                currentUser(),
                body.get("categoryId"),
                body.get("customPrompt"),
                parseDateTime(body.get("scheduledFor")),
                parseRecurrence(body.get("recurrence"))
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Post scheduled.", saved));
    }

    // PUT /social-post/schedule/{id} -> only while still PENDING
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ScheduledPost>> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        ScheduledPost saved = scheduledPostService.update(
                id,
                currentUser(),
                body.get("categoryId"),
                body.get("customPrompt"),
                body.containsKey("scheduledFor") ? parseDateTime(body.get("scheduledFor")) : null,
                body.containsKey("recurrence") ? parseRecurrence(body.get("recurrence")) : null
        );
        return ResponseEntity.ok(ApiResponse.success("Scheduled post updated.", saved));
    }

    // GET /social-post/schedule/{id}/series -> every past/future occurrence of this post's recurring series
    @GetMapping("/{id}/series")
    public ResponseEntity<ApiResponse<List<ScheduledPost>>> series(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(scheduledPostService.seriesHistory(id, currentUser())));
    }

    // POST /social-post/schedule/{id}/cancel -> only while still PENDING
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<ScheduledPost>> cancel(@PathVariable Long id) {
        ScheduledPost saved = scheduledPostService.cancel(id, currentUser());
        return ResponseEntity.ok(ApiResponse.success("Scheduled post cancelled.", saved));
    }

    // DELETE /social-post/schedule/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        scheduledPostService.delete(id, currentUser());
        return ResponseEntity.ok(ApiResponse.deleteSuccess(id));
    }

    private RecurrenceType parseRecurrence(String raw) {
        if (raw == null || raw.isBlank()) {
            return RecurrenceType.NONE;
        }
        try {
            return RecurrenceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid recurrence: " + raw + " (expected NONE, WEEKLY, or MONTHLY)");
        }
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // Accepts "2026-08-20T14:30:00" (from a <input type="datetime-local">)
            // as well as full offset/instant forms like "...Z" or "...+05:30".
            String normalized = raw.trim();
            if (normalized.endsWith("Z")) {
                return java.time.Instant.parse(normalized)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime();
            }
            if (normalized.length() > 19 && (normalized.contains("+") || normalized.lastIndexOf('-') > 10)) {
                return java.time.OffsetDateTime.parse(normalized)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDateTime();
            }
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid scheduledFor date/time: " + raw);
        }
    }
}