package com.example.backend.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single scheduled AI-post job, sitting on top of the existing AI Posting
 * / Manual Prompting flow (SocialHubController -> MistralService ->
 * LinkedInService). This entity doubles as both the "schedule" (while
 * PENDING) and the "execution history" record (once it has run) so a
 * schedule and its outcome are always the same row — nothing else about the
 * existing generation/posting logic is touched or duplicated here.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "scheduled_posts")
public class ScheduledPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Username of the employee who created this scheduled job.
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    // --- What to generate -------------------------------------------------
    // Optional category id (matches the frontend CATEGORIES list, e.g.
    // "technical"). When set alongside customPrompt, both are passed through
    // to the existing MistralService.generatePost(topic, categoryId) exactly
    // as the AI-mode UI would.
    @Column(name = "category_id")
    private String categoryId;

    // Optional free-form prompt (the "Technical Content Prompt" / manual
    // prompt text). When present this is used as the "topic" sent to
    // MistralService.generatePost, exactly like Manual Prompting mode does
    // today. When absent, a topic is chosen automatically at run time from
    // the same built-in category/topic list the AI-mode UI offers, so the
    // existing AI posting flow behaves exactly as it does today.
    @Column(name = "custom_prompt", columnDefinition = "TEXT")
    private String customPrompt;

    // --- Scheduling ---------------------------------------------------------
    @Column(name = "scheduled_for", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduledPostStatus status = ScheduledPostStatus.PENDING;

    // How often this schedule repeats. Defaults to a single one-time run.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurrenceType recurrence = RecurrenceType.NONE;

    // Groups every occurrence of a recurring schedule together (all rows
    // spawned from the same original "Weekly"/"Monthly" post share this id),
    // so the UI can show "this is part of a weekly series" and the full
    // history of a series can be found even though each occurrence is its
    // own row. A one-time (NONE) post is simply a series of one.
    @Column(name = "series_id", nullable = false)
    private String seriesId;

    // --- Execution results (filled in once the job has run) ---------------
    // The topic actually used for generation (either customPrompt, or the
    // auto-picked topic when customPrompt was left blank).
    @Column(name = "resolved_topic", columnDefinition = "TEXT")
    private String resolvedTopic;

    @Column(name = "generated_content", columnDefinition = "TEXT")
    private String generatedContent;

    // Best-effort reference to the published post (LinkedIn's UGC API
    // doesn't return a permalink, so this is the service's own success
    // message plus the generated content above, which together stand in for
    // a "link to the generated post").
    @Column(name = "post_result_message")
    private String postResultMessage;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "executed_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime executedAt;

    @Column(name = "created_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ScheduledPostStatus.PENDING;
        }
        if (this.recurrence == null) {
            this.recurrence = RecurrenceType.NONE;
        }
        if (this.seriesId == null) {
            this.seriesId = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}