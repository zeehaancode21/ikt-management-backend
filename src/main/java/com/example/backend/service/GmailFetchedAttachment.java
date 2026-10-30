package com.example.backend.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata for a single attachment from a Gmail message.
 * Used by GmailFetchedMessage to describe attachments to be downloaded.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GmailFetchedAttachment {

    /**
     * Gmail's unique ID for this attachment within the message.
     * Used to fetch the actual attachment bytes.
     */
    private String gmailAttachmentId;

    /**
     * Position of the file inside the Gmail message. Unlike gmailAttachmentId this stays the
     * same for a given message, so it is what lets us find the file again later.
     */
    private String partId;

    /**
     * Original filename of the attachment.
     */
    private String filename;

    /**
     * MIME type (e.g. "application/pdf", "image/jpeg").
     */
    private String mimeType;

    /**
     * Size in bytes.
     */
    private Long size;
}