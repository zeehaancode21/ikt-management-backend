package com.example.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a complete Gmail message fetched from Gmail API.
 * Used by RfiGmailSyncService to import new messages into RfiEmailMessage entities.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GmailFetchedMessage {

    /**
     * Gmail's unique message ID.
     */
    private String gmailMessageId;

    /**
     * Gmail thread ID (groups all messages in a conversation).
     */
    private String gmailThreadId;

    /**
     * RFC 2822 In-Reply-To header (if present).
     * References the parent message's Message-ID.
     */
    private String inReplyTo;

    /**
     * RFC 2822 References header (if present).
     * Lists all message IDs in the thread chain.
     */
    private String references;

    /**
     * Sender's email address.
     */
    private String fromAddress;

    /**
     * Primary recipient's email address.
     */
    private String toAddress;

    /**
     * CC recipients as written in the Cc header (may be null).
     */
    private String ccAddress;

    /**
     * RFC 5322 Message-ID header (same value in every mailbox).
     */
    private String rfcMessageId;

    /**
     * Email subject.
     */
    private String subject;

    /**
     * Full email body text (may be truncated by Gmail API).
     */
    private String bodyText;

    /**
     * Gmail API snippet (text preview).
     */
    private String snippet;

    /**
     * When the message was sent/received.
     */
    private LocalDateTime messageDate;

    /**
     * List of attachments (if any).
     */
    private List<GmailFetchedAttachment> attachments = new ArrayList<>();
}