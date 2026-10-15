package com.example.backend.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holds the result of a successful Gmail send operation.
 * Returned by GmailEmailService.sendWithAttachment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GmailSendResult {

    /**
     * The Gmail message ID of the sent message.
     * Used to prevent duplicate imports during later syncs.
     */
    private String messageId;

    /**
     * The Gmail thread ID of the sent message.
     * Stored on the RFI to group all replies in the same conversation.
     */
    private String threadId;
}