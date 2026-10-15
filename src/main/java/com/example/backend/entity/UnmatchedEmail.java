package com.example.backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stores emails that the Gmail sync job couldn't confidently match to an RFI.
 * 
 * This typically happens when:
 * 1. A subject search returned ambiguous/multiple results (multiple threads matched the subject).
 * 2. An email reply came from an unexpected sender.
 * 3. A thread ID lookup failed or was unclear.
 * 
 * Admins can review these and manually assign them to the correct RFI if needed.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "unmatched_emails", indexes = {
    @Index(name = "idx_unmatched_gmail_thread_id", columnList = "gmail_thread_id"),
    @Index(name = "idx_unmatched_resolved", columnList = "resolved"),
    @Index(name = "idx_unmatched_created_at", columnList = "created_at")
})
public class UnmatchedEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Gmail thread ID of the unmatched message(s).
     */
    @Column(name = "gmail_thread_id", nullable = false)
    private String gmailThreadId;

    /**
     * Email subject line (from the Gmail message).
     */
    @Column(name = "subject", columnDefinition = "TEXT", nullable = true)
    private String subject;

    /**
     * Project name of the candidate RFI that might match.
     * Set during the matching attempt so admins know where to look.
     */
    @Column(name = "candidate_project_name", nullable = true)
    private String candidateProjectName;

    /**
     * RFI number of the candidate that might match.
     * Set during the matching attempt.
     */
    @Column(name = "candidate_rfi_number", nullable = true)
    private String candidateRfiNumber;

    /**
     * Explanation of why this email could not be automatically matched.
     */
    @Column(name = "reason", columnDefinition = "TEXT", nullable = false)
    private String reason;

    /**
     * True if an admin has manually reviewed and resolved this email
     * (either assigned it to an RFI or confirmed it should be ignored).
     */
    @Column(name = "resolved", nullable = false)
    private Boolean resolved = false;

    /**
     * When this unmatched email record was created.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * When an admin last reviewed/updated this record.
     */
    @Column(name = "resolved_at", nullable = true)
    private LocalDateTime resolvedAt;

    /**
     * Notes from the admin who resolved the email (if applicable).
     */
    @Column(name = "resolution_note", columnDefinition = "TEXT", nullable = true)
    private String resolutionNote;
}