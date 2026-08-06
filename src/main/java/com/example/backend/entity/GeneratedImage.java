// GeneratedImage.java
package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single AI-generated image option for a Media Hub post draft.
 *
 * A "draft" is identified by an opaque {@code draftId} the frontend
 * generates when the user starts composing a post (see SocialHub.tsx). Every
 * image generated for that draft — whether or not it ends up being the one
 * the user finalizes — is stored here so that:
 *   1) the set of options can be re-rendered without re-calling the AI
 *      provider, and
 *   2) the finalized (selected) image survives a page reload/revisit of the
 *      draft before the user actually publishes.
 *
 * Rows are cleaned up once the post is published (or discarded), see
 * {@code GeneratedImageRepository#deleteByDraftIdAndCreatedBy}.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "generated_images", indexes = {
        @Index(name = "idx_generated_images_draft", columnList = "draftId, createdBy")
})
public class GeneratedImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String draftId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGBLOB")
    private byte[] imageData;

    @Column(nullable = false, length = 64)
    private String contentType;

    /**
     * Whether this is the option the user has finalized for the draft. Only
     * one row per (draftId, createdBy) should have this set to true at a
     * time — enforced in AiImageController via a clear-then-set update.
     */
    @Column(nullable = false)
    private boolean selected = false;

    @Column(nullable = false, length = 128)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}