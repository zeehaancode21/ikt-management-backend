package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Immutable audit trail. A row is written for every TOTP unlock, every
 * decrypted view, and every file download against the confidential vault —
 * so there is always a record of who accessed what confidential data, when.
 */
@Entity
@Table(name = "vault_audit_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VaultAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String actorUsername;     // who performed the action (the owner)
    private String action;            // UNLOCK, VIEW_EMPLOYEE, DOWNLOAD_DOCUMENT, VIEW_BANK_DETAILS, FAILED_CODE
    private String targetEmployee;    // whose data was accessed (nullable for UNLOCK/FAILED_CODE)
    private String detail;            // e.g. doc type or file name
    private String ipAddress;

    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        timestamp = LocalDateTime.now();
    }
}