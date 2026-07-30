package com.example.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// "Permission" — hours-based time away for part of a day (e.g. a doctor's
// appointment), requested/approved independently of the day-based Leave
// pool. Governed by quotas enforced server-side in PermissionController:
//   • Max PermissionController.MAX_HOURS_PER_DAY hours on any single day
//   • Max PermissionController.MAX_HOURS_PER_MONTH hours across a month
//   • Max PermissionController.MAX_REQUESTS_PER_MONTH requests across a month
//
// Indexes below back the query patterns used across PermissionController /
// PermissionSurplusService: per-employee lookups filtered by status and/or
// date range (quota + summary aggregates), and the owner's pending queue
// (status + createdDate ordering).
@Entity
@Table(indexes = {
        @Index(name = "idx_permission_employee_status", columnList = "employeeName,status"),
        @Index(name = "idx_permission_employee_date", columnList = "employeeName,date"),
        @Index(name = "idx_permission_status_created", columnList = "status,createdDate"),
        @Index(name = "idx_permission_status_date", columnList = "status,date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeName;

    @Enumerated(EnumType.STRING)
    private PermissionType permissionType;

    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;

    // Duration in hours between startTime and endTime, rounded to the
    // nearest quarter-hour. Computed server-side — never trust a client-sent
    // value for this.
    private Double hours;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdDate;

    // ── Reapproval (change-request) fields ──────────────────────────────
    // Populated only while status == REAPPROVAL_PENDING. They hold the
    // employee's *proposed* changes to an already-approved permission. The
    // original date/startTime/etc above are left untouched until an
    // OWNER/ADMIN/MANAGER approves the change, at which point these values
    // are copied over the real fields and cleared again.
    private LocalDate pendingDate;
    private LocalTime pendingStartTime;
    private LocalTime pendingEndTime;
    private Double pendingHours;

    @Enumerated(EnumType.STRING)
    private PermissionType pendingPermissionType;

    @Column(length = 500)
    private String pendingReason;
}