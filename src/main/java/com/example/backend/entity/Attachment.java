// Attachment.java
package com.example.backend.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A file the app knows about. Two kinds:
 *
 *  1. Stored file  - the bytes live in table attachment_data (LONGBLOB). Used for chat uploads and
 *                    anything that does not come from / go through a Gmail thread.
 *
 *  2. Gmail-backed - NO bytes are stored here. The row only remembers WHERE the file lives inside
 *                    the Gmail thread (message id + part id + attachment id + whose mailbox).
 *                    "View" and "Download" are streamed straight from Gmail on demand. This is
 *                    used for every file on an RFI email thread (the PDF sent with the RFI,
 *                    follow-up files, and every file in a reply), so those files cost ~0 bytes in
 *                    the Railway database.
 *
 * The gmail* columns are @JsonIgnore on purpose: they are internal pointers (they name a mailbox
 * and a Gmail message) and must never be sent to the browser. The browser keeps using the same
 * /attachments/{id}/preview and /attachments/{id}/download URLs for both kinds.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "attachments")
public class Attachment {

    /** filePath prefix for files that live in the database (attachment_data). */
    public static final String DB_PATH_PREFIX = "db:";
    /** filePath prefix for files that live in a Gmail thread (nothing stored here). */
    public static final String GMAIL_PATH_PREFIX = "gmail:";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false)
    private String fileType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String uploadedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;

    // ── Gmail pointer (all NULL for files stored in the database) ──────────────

    /** Gmail's id of the message that carries the file. */
    @JsonIgnore
    @Column(name = "gmail_message_id")
    private String gmailMessageId;

    /**
     * Gmail's attachment id at the time we last saw it. Gmail may hand out a different id for the
     * same file later, so this is only a fast path: if it stops working the file is found again
     * through gmailPartId (see GmailEmailService.openAttachment) and this value is refreshed.
     */
    @JsonIgnore
    @Column(name = "gmail_attachment_id", columnDefinition = "TEXT")
    private String gmailAttachmentId;

    /** Position of the file inside the message (stable for a given message). */
    @JsonIgnore
    @Column(name = "gmail_part_id")
    private String gmailPartId;

    /** Username whose connected Gmail mailbox holds the message (its RFI Settings token is used). */
    @JsonIgnore
    @Column(name = "gmail_account")
    private String gmailAccount;

    /** True when the file is read from Gmail instead of from attachment_data. */
    @JsonIgnore
    public boolean isGmailBacked() {
        return gmailMessageId != null && !gmailMessageId.isBlank();
    }
}