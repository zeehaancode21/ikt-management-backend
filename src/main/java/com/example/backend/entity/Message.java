package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// Note: @PostLoad is already covered by the jakarta.persistence.* wildcard import above.

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "messages", 
       indexes = {
           @Index(name = "idx_sender_receiver", columnList = "senderUsername, receiverUsername"),
           @Index(name = "idx_sent_at", columnList = "sentAt DESC"),
           @Index(name = "idx_receiver_sender_read", columnList = "receiverUsername, senderUsername, readByReceiver")
       })
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String senderUsername;

    @Column(nullable = false)
    private String receiverUsername;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private boolean readByReceiver = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime sentAt;

    // JSON-encoded emoji reactions, e.g. {"👍":["alice","bob"],"❤️":["carol"]}.
    // Stored as a raw JSON string (same convention as GroupMessage.pollData)
    // rather than a separate table, since reaction sets per message are small.
    @Column(columnDefinition = "TEXT")
    private String reactions;

    // EAGER fetch ensures attachments are loaded within the same transaction.
    // Previously LAZY caused "could not initialize proxy - no Session" errors
    // because open-in-view is disabled and the session closes before Jackson
    // serializes the collection.
    // @BatchSize still applies: Hibernate loads all attachments for a page of
    // messages in a single IN(...) query rather than one query per message.
    @BatchSize(size = 25)
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "message_attachments",
        joinColumns = @JoinColumn(name = "message_id"),
        inverseJoinColumns = @JoinColumn(name = "attachment_id")
    )
    private List<Attachment> attachments = new ArrayList<>();

    // ── Reply-to-message ──────────────────────────────────────────────────
    // The persisted FK. This is the only reply-related column actually
    // stored in the DB; everything else below is derived from the message
    // it points to.
    @Column(name = "reply_to_id")
    private Long replyToId;

    // Read-only self-relation mapped to the same column, used purely so
    // repository queries can LEFT JOIN FETCH it and avoid N+1 lookups.
    // insertable/updatable = false because replyToId above owns the column.
    // OnDelete(SET_NULL): if the original message is later deleted, this FK
    // is nulled out at the DB level instead of blocking the delete — a
    // message quoting a deleted message just loses its quote, same as the
    // existing "this cannot be undone" delete flows expect.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_id", insertable = false, updatable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.SET_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Message replyTo;

    // Denormalized snapshot of the original message, sent to the frontend
    // so it can render the quote without a second round trip. Populated by
    // populateReplyMeta() below, never persisted.
    @Transient
    private String replyToSender;

    @Transient
    private String replyToContent;

    @Transient
    private Boolean replyToHasAttachment;

    // Fires whenever this entity is loaded from the DB (findById, list/page
    // queries, etc). If the query fetch-joined `replyTo`, this reads it with
    // no extra query; if not, accessing it lazily still works inside a
    // @Transactional method, just with one extra SELECT.
    @PostLoad
    public void populateReplyMeta() {
        if (replyTo != null) {
            this.replyToSender = replyTo.getSenderUsername();
            this.replyToContent = replyTo.getContent();
            this.replyToHasAttachment = replyTo.getAttachments() != null && !replyTo.getAttachments().isEmpty();
        }
    }
}