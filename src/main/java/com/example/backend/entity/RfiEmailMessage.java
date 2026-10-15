// package com.example.backend.entity;

// import java.time.LocalDateTime;
// import java.util.ArrayList;
// import java.util.List;

// import jakarta.persistence.Column;
// import jakarta.persistence.Entity;
// import jakarta.persistence.EnumType;
// import jakarta.persistence.Enumerated;
// import jakarta.persistence.FetchType;
// import jakarta.persistence.GeneratedValue;
// import jakarta.persistence.GenerationType;
// import jakarta.persistence.Id;
// import jakarta.persistence.Index;
// import jakarta.persistence.JoinColumn;
// import jakarta.persistence.JoinTable;
// import jakarta.persistence.ManyToMany;
// import jakarta.persistence.ManyToOne;
// import jakarta.persistence.Table;
// import lombok.AllArgsConstructor;
// import lombok.Data;
// import lombok.NoArgsConstructor;

// /**
//  * Stores each email message (sent or received) in an RFI conversation.
//  * Links to the RFI and tracks Gmail identifiers for deduplication and threading.
//  */
// @Entity
// @Data
// @NoArgsConstructor
// @AllArgsConstructor
// @Table(name = "rfi_email_messages", indexes = {
//     @Index(name = "idx_rfi_email_rfi_id", columnList = "rfi_id"),
//     @Index(name = "idx_rfi_email_gmail_msg_id", columnList = "gmail_message_id"),
//     @Index(name = "idx_rfi_email_gmail_thread_id", columnList = "gmail_thread_id"),
//     @Index(name = "idx_rfi_email_direction", columnList = "direction"),
//     @Index(name = "idx_rfi_email_date", columnList = "message_date")
// })
// public class RfiEmailMessage {

//     @Id
//     @GeneratedValue(strategy = GenerationType.IDENTITY)
//     private Long id;

//     @ManyToOne(fetch = FetchType.LAZY)
//     @JoinColumn(name = "rfi_id", nullable = false)
//     private Rfi rfi;

//     @Enumerated(EnumType.STRING)
//     @Column(nullable = false)
//     private RfiEmailDirection direction;

//     /**
//      * Unique Gmail message ID. Used to prevent duplicate imports from multiple syncs.
//      * Indexed with unique constraint handled at the database level to allow NULL values
//      * (for messages not yet synced from Gmail).
//      */
//     @Column(name = "gmail_message_id", unique = true, nullable = true)
//     private String gmailMessageId;

//     /**
//      * Gmail thread ID. Matches Rfi.gmailThreadId for consistency.
//      */
//     @Column(name = "gmail_thread_id", nullable = true)
//     private String gmailThreadId;

//     /**
//      * RFC 2822 In-Reply-To header value (if present).
//      * Helps identify which message this is a reply to.
//      */
//     @Column(name = "in_reply_to", columnDefinition = "TEXT")
//     private String inReplyTo;

//     /**
//      * RFC 2822 References header value (if present).
//      * Lists all message IDs in the thread chain.
//      */
//     @Column(name = "email_references", columnDefinition = "TEXT")
//     private String references;

//     /**
//      * Sender's email address.
//      */
//     @Column(name = "from_address", nullable = false)
//     private String fromAddress;

//     /**
//      * Primary recipient's email address.
//      */
//     @Column(name = "to_address", nullable = false)
//     private String toAddress;

//     /**
//      * Comma-separated CC recipients, if any (mirrors Rfi.ccEmails). Nullable
//      * — most messages have no CC.
//      */
//     @Column(name = "cc_address")
//     private String ccAddress;

//     /**
//      * Email subject line.
//      */
//     @Column(name = "subject", columnDefinition = "TEXT")
//     private String subject;

//     /**
//      * Full email body text (may be truncated by Gmail API).
//      */
//     @Column(name = "body_text", columnDefinition = "LONGTEXT")
//     private String bodyText;

//     /**
//      * Gmail API snippet (preview).
//      */
//     @Column(name = "snippet", columnDefinition = "TEXT")
//     private String snippet;

//     /**
//      * Date/time the email was sent or received.
//      */
//     @Column(name = "message_date", nullable = false)
//     private LocalDateTime messageDate;

//     /**
//      * Timestamp when this message was imported into our system.
//      */
//     @Column(name = "imported_at", nullable = false)
//     private LocalDateTime importedAt = LocalDateTime.now();

//     /**
//      * Attachments associated with this email message.
//      * EAGER loading because we always need attachment metadata when displaying the message.
//      */
//     @ManyToMany(fetch = FetchType.EAGER)
//     @JoinTable(
//         name = "rfi_email_attachments",
//         joinColumns = @JoinColumn(name = "email_message_id"),
//         inverseJoinColumns = @JoinColumn(name = "attachment_id"),
//         indexes = {
//             @Index(name = "idx_rfi_email_att_email", columnList = "email_message_id"),
//             @Index(name = "idx_rfi_email_att_file", columnList = "attachment_id")
//         }
//     )
//     private List<Attachment> attachments = new ArrayList<>();
// }
package com.example.backend.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stores each email message (sent or received) in an RFI conversation.
 * Links to the RFI and tracks Gmail identifiers for deduplication and threading.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rfi_email_messages", indexes = {
    @Index(name = "idx_rfi_email_rfi_id", columnList = "rfi_id"),
    @Index(name = "idx_rfi_email_gmail_msg_id", columnList = "gmail_message_id"),
    @Index(name = "idx_rfi_email_gmail_thread_id", columnList = "gmail_thread_id"),
    @Index(name = "idx_rfi_email_direction", columnList = "direction"),
    @Index(name = "idx_rfi_email_date", columnList = "message_date")
})
public class RfiEmailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfi_id", nullable = false)
    private Rfi rfi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RfiEmailDirection direction;

    /**
     * Unique Gmail message ID. Used to prevent duplicate imports from multiple syncs.
     * Indexed with unique constraint handled at the database level to allow NULL values
     * (for messages not yet synced from Gmail).
     */
    @Column(name = "gmail_message_id", unique = true, nullable = true)
    private String gmailMessageId;

    /**
     * Gmail thread ID. Matches Rfi.gmailThreadId for consistency.
     */
    @Column(name = "gmail_thread_id", nullable = true)
    private String gmailThreadId;

    /**
     * RFC 2822 In-Reply-To header value (if present).
     * Helps identify which message this is a reply to.
     */
    @Column(name = "in_reply_to", columnDefinition = "TEXT")
    private String inReplyTo;

    /**
     * RFC 2822 References header value (if present).
     * Lists all message IDs in the thread chain.
     */
    @Column(name = "email_references", columnDefinition = "TEXT")
    private String references;

    /**
     * Sender's email address.
     */
    @Column(name = "from_address", nullable = false)
    private String fromAddress;

    /**
     * Primary recipient's email address.
     */
    @Column(name = "to_address", nullable = false)
    private String toAddress;

    /**
     * Comma-separated CC recipients, if any (mirrors Rfi.ccEmails). Nullable
     * — most messages have no CC.
     */
    @Column(name = "cc_address")
    private String ccAddress;

    /**
     * Email subject line.
     */
    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    /**
     * Full email body text (may be truncated by Gmail API).
     */
    @Column(name = "body_text", columnDefinition = "LONGTEXT")
    private String bodyText;

    /**
     * Gmail API snippet (preview).
     */
    @Column(name = "snippet", columnDefinition = "TEXT")
    private String snippet;

    /**
     * Date/time the email was sent or received.
     */
    @Column(name = "message_date", nullable = false)
    private LocalDateTime messageDate;

    /**
     * Timestamp when this message was imported into our system.
     */
    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt = LocalDateTime.now();

    /**
     * Attachments associated with this email message.
     * EAGER loading because we always need attachment metadata when displaying the message.
     */
    // @BatchSize: when many messages are loaded (e.g. the email thread), Hibernate loads their
    // attachments in batches of up to 50 messages per query instead of one query per message.
    @BatchSize(size = 50)
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "rfi_email_attachments",
        joinColumns = @JoinColumn(name = "email_message_id"),
        inverseJoinColumns = @JoinColumn(name = "attachment_id"),
        indexes = {
            @Index(name = "idx_rfi_email_att_email", columnList = "email_message_id"),
            @Index(name = "idx_rfi_email_att_file", columnList = "attachment_id")
        }
    )
    private List<Attachment> attachments = new ArrayList<>();
}