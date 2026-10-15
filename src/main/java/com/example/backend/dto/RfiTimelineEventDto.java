package com.example.backend.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.example.backend.entity.Attachment;
import com.example.backend.entity.RfiStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single event in the RFI communication/status timeline.
 * Used to render the chronological history: RFI Created → Email Sent → Response Received → Status Changed
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfiTimelineEventDto {

    /**
     * Event type: RFI_CREATED, EMAIL_SENT, REPLY_RECEIVED, STATUS_CHANGED
     */
    private String type;

    /**
     * When this event occurred.
     */
    private LocalDateTime timestamp;

    /**
     * For EMAIL_SENT/REPLY_RECEIVED: sender's email address.
     */
    private String sender;

    /**
     * For EMAIL_SENT/REPLY_RECEIVED: recipient's email address.
     */
    private String recipient;
    
    /**
     * For EMAIL_SENT/REPLY_RECEIVED: comma-separated CC address(es), if any.
     */
    private String cc;

    /**
     * Email subject line (for email events).
     */
    private String subject;

    /**
     * Event description or message content.
     */
    private String message;

    /**
     * Attachments associated with this event (if any).
     */
    private List<Attachment> attachments = new ArrayList<>();

    /**
     * Gmail message ID (for email events, tracking purposes).
     */
    private String gmailMessageId;

    /**
     * Gmail thread ID (for email events).
     */
    private String gmailThreadId;

    /**
     * For STATUS_CHANGED events: the old status.
     */
    private RfiStatus fromStatus;

    /**
     * For STATUS_CHANGED events: the new status.
     */
    private RfiStatus toStatus;

    /**
     * Who/what performed this action (RFI creator, Gmail sync system, etc.).
     */
    private String performedBy;

    /**
     * Only meaningful for the email event that is the RFI itself (EMAIL_SENT).
     * True when the person viewing the timeline RECEIVED that email (they are
     * on the To/CC list and did not send it), so the app can label it
     * "RFI Received" instead of "Email Sent". Filled in per viewer, never stored.
     */
    private boolean receivedByViewer;
}