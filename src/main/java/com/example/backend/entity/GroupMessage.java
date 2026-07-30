package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "group_messages")
public class GroupMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Group group;

    @Transient
    private Long groupId;

    @Column(nullable = false)
    private String senderUsername;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String messageType = "MESSAGE";

    @Column(columnDefinition = "TEXT")
    private String pollData;

    // JSON-encoded emoji reactions, e.g. {"👍":["alice","bob"],"❤️":["carol"]}.
    @Column(columnDefinition = "TEXT")
    private String reactions;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime sentAt;

    // Changed LAZY → EAGER to fix "could not initialize proxy - no Session" crash
    // when group messages with attachments are serialized to JSON.
    @BatchSize(size = 25)
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "group_message_attachments",
        joinColumns = @JoinColumn(name = "group_message_id"),
        inverseJoinColumns = @JoinColumn(name = "attachment_id")
    )
    private List<Attachment> attachments = new ArrayList<>();

    // ── Reply-to-message ──────────────────────────────────────────────────
    // The persisted FK. Only reply-related column actually stored in the DB.
    @Column(name = "reply_to_id")
    private Long replyToId;

    // Read-only self-relation mapped to the same column, used so repository
    // queries can LEFT JOIN FETCH it and avoid N+1 lookups.
    // OnDelete(SET_NULL): if the original message is later deleted, this FK
    // is nulled out at the DB level instead of blocking the delete.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_id", insertable = false, updatable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.SET_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private GroupMessage replyTo;

    // Denormalized snapshot sent to the frontend, never persisted.
    @Transient
    private String replyToSender;

    @Transient
    private String replyToContent;

    @Transient
    private Boolean replyToHasAttachment;

    @PostLoad
    public void postLoad() {
        if (group != null) this.groupId = group.getId();
        if (replyTo != null) {
            this.replyToSender = replyTo.getSenderUsername();
            this.replyToContent = replyTo.getContent();
            this.replyToHasAttachment = replyTo.getAttachments() != null && !replyTo.getAttachments().isEmpty();
        }
    }
}