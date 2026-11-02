package com.example.backend.entity;

import java.time.LocalDateTime;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rfis",
    // Two RFIs of one project can never share a number (see RfiServiceImpl#nextRfiNumber).
    // If the database already has duplicates this constraint cannot be created - run
    // sql/01_check_duplicates.sql first.
    uniqueConstraints = @UniqueConstraint(name = "uq_rfi_project_number", columnNames = {"project_name", "rfi_number"}),
    indexes = {
    // idx_rfi_project was removed: the composite indexes below already start with project_name.
    // Column NAMES (snake_case) are required here, not Java field names.
    @Index(name = "idx_rfi_project_filters", columnList = "project_name, team_type, discipline"),
    @Index(name = "idx_rfi_gmail_thread_id", columnList = "gmail_thread_id"),
    @Index(name = "idx_rfi_project_priority", columnList = "project_name, priority"),
    // The 60-second reply poll filters on sent_by + status.
    @Index(name = "idx_rfi_sent_by_status", columnList = "sent_by, status")
})
public class Rfi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    // e.g. "RFI#001" — unique + sequential per project, see RfiServiceImpl#nextRfiNumber
    @Column(name = "rfi_number", nullable = false)
    private String rfiNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RfiDiscipline discipline;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_type", nullable = false)
    private RfiTeamType teamType;

    @Column(nullable = false)
    private String subject;

    // How urgently this RFI needs an answer. Defaults to MEDIUM.
    // columnDefinition carries a DB-level DEFAULT on purpose: this project runs
    // with ddl-auto=update, and adding a NOT NULL column to a table that already
    // has rows only works if the database can back-fill them. Every RFI created
    // before this field existed becomes MEDIUM automatically.
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16,
            columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'")
    private RfiPriority priority = RfiPriority.DEFAULT;

    // PROJECTNAME_RFI#NO_SUBJECTNAME_DD-MM-YYYY — generated once at send time, never edited after
    @Column(name = "generated_email_subject", nullable = false)
    private String generatedEmailSubject;

    @Column(name = "email_body", columnDefinition = "TEXT", nullable = false)
    private String emailBody;

    // Snapshot of ProjectStatus.rfiRecipientEmail at send time, so a later
    // change to the project's recipient doesn't rewrite history.
    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    // Comma-separated list of CC recipients captured at send time (mirrors
    // recipientEmail's "snapshot" behavior — editing favorites later never
    // rewrites what an already-sent RFI shows it was CC'd to). Nullable:
    // most RFIs have no CC at all.
    @Column(name = "cc_emails")
    private String ccEmails;

    // EAGER: open-in-view is disabled on this project, so LAZY here would
    // throw "could not initialize proxy - no Session" on serialization —
    // same reasoning already documented on Message.attachments.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "attachment_id", nullable = false)
    private Attachment attachment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RfiStatus status;

    @Column(name = "sent_by")
    private String sentBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    // Display label for the sender picked in the dropdown (e.g. formatted
    // username) — cosmetic only, shown in the RFI list. senderEmail is the
    // field actually used as Reply-To.
    @Column(name = "sender_name")
    private String senderName;

    // ── Gmail thread and response tracking ───────────────────────────────
    /**
     * Gmail thread ID for this RFI conversation.
     * Set when the RFI is first sent (from the send result).
     * Used to fetch all subsequent replies in the same thread.
     */
    @Column(name = "gmail_thread_id", nullable = true)
    private String gmailThreadId;

    /**
     * Timestamp when the most recent reply was received.
     * Updated by RfiGmailSyncService when new incoming emails are detected.
     */
    @Column(name = "response_received_at")
    private LocalDateTime responseReceivedAt;

    /**
     * Optional notes/summary of the response(s) received.
     * Can be populated manually by the user when reviewing a response.
     */
    @Column(name = "response_notes", columnDefinition = "TEXT")
    private String responseNotes;

    // ── Internal workflow (Drafter -> Checker -> Owner -> Client) ─────────
    // All nullable: plain external RFIs and RFIs created before this workflow have none of it.
    // The Drafter is the existing sentBy; people are stored as usernames, like sentBy.

    /** The colleague an INTERNAL RFI is sent to for review (resolved from the To address). */
    @Column(name = "checker_username")
    private String checkerUsername;

    /** The Owner (project lead) who turns the approved RFI into an external one and dispatches it. */
    @Column(name = "owner_username")
    private String ownerUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "internal_stage", length = 32)
    private RfiInternalStage internalStage;

    /** On the generated external draft: id of the approved internal RFI it came from. */
    @Column(name = "source_rfi_id")
    private Long sourceRfiId;

    /** On the approved internal RFI: id of the external draft / RFI generated from it. */
    @Column(name = "forwarded_rfi_id")
    private Long forwardedRfiId;

    // ── Per-viewer access info (NOT stored in the database) ──────────────
    // Filled in by RfiAccessService for whoever is looking at the RFI right
    // now, so the app knows whether to show the action buttons.
    // @Transient = JPA ignores it, no database column is created.
    @Transient
    private RfiParticipantRole participantRole;

    @Transient
    private boolean canModify;

    // True when the person looking at this RFI RECEIVED it (their email is on the
    // To/CC list and they are not the one who sent it). The app uses it to show
    // "Received" instead of "Sent" - e.g. an employee who gets an INTERNAL RFI.
    // Not stored in the database.
    @Transient
    private boolean receivedByViewer;

    // The viewer's part in the internal workflow on THIS RFI: DRAFTER, CHECKER, OWNER or null.
    // The app uses it to decide which workflow buttons to show. Not stored in the database.
    @Transient
    private String internalRole;

    // Safety net: a null here (e.g. built via the all-args constructor) must never
    // reach the NOT NULL column.
    @PrePersist
    void applyDefaults() {
        if (priority == null) {
            priority = RfiPriority.DEFAULT;
        }
    }
}