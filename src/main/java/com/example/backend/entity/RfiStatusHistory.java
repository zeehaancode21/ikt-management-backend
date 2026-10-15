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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit trail of every status change on an RFI.
 * Preserves who changed the status, when, and why (note/reason).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rfi_status_history", indexes = {
    @Index(name = "idx_rfi_status_hist_rfi_id", columnList = "rfi_id"),
    @Index(name = "idx_rfi_status_hist_changed_at", columnList = "changed_at"),
    @Index(name = "idx_rfi_status_hist_new_status", columnList = "new_status")
})
public class RfiStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfi_id", nullable = false)
    private Rfi rfi;

    /**
     * The status before this change. Null for the initial creation event.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "old_status")
    private RfiStatus oldStatus;

    /**
     * The status after this change.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false)
    private RfiStatus newStatus;

    /**
     * Username (in-app action) or sender email (inbound email) of who made this change.
     */
    @Column(name = "changed_by", nullable = false)
    private String changedBy;

    /**
     * When this status change occurred.
     */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();

    /**
     * Optional note explaining the reason for the status change.
     */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /**
     * NEW - whether the person who caused this change was on the TO or CC list.
     * Nullable so every older history row keeps working unchanged.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role")
    private RfiParticipantRole actorRole;

    /**
     * NEW - where the change came from (APP or EMAIL). Nullable for older rows.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source")
    private RfiActionSource source;
}