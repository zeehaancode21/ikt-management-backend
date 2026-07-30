package com.example.backend.entity;

import java.time.LocalDate; 
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate fromDate;
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    private DateType dateType;

    // Only relevant when dateType == HALF_DAY. e.g. "FIRST_HALF" / "SECOND_HALF".
    private String halfSession;

    // Only relevant when dateType == SHORT_LEAVE. Number of hours requested
    // for that single day (e.g. 1.0, 1.5, 2.0). Capped at 2 hrs/day and
    // 4 hrs/month — enforced in LeaveController.
    private Double hours;

    private String employeeName;
    private String reason;

    @Enumerated(EnumType.STRING)
    private Status status=Status.PENDING;

    @Enumerated(EnumType.STRING)
    private LeaveType leaveType;

    private double days;

    private int leaveTaken;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdDate;

    // ── Reapproval (change-request) fields ──────────────────────────────
    // Populated only while status == REAPPROVAL_PENDING. They hold the
    // employee's *proposed* changes to an already-approved leave. The
    // original fromDate/toDate/etc above are left untouched until an
    // OWNER/ADMIN/MANAGER approves the change, at which point these values
    // are copied over the real fields and cleared again.
    private LocalDate pendingFromDate;
    private LocalDate pendingToDate;

    @Enumerated(EnumType.STRING)
    private DateType pendingDateType;

    private String pendingHalfSession;

    // Mirrors `hours` for a proposed change to a SHORT_LEAVE request.
    private Double pendingHours;

    @Enumerated(EnumType.STRING)
    private LeaveType pendingLeaveType;

    private String pendingReason;

    private Double pendingDays;

    // ── Auto-generated "Half-Day Permission" / "Full-Day Permission" leave ─
    // When an employee's cumulative APPROVED Permission-portal hours for a
    // calendar month reach 4h (Half-Day Permission, days=0.5) or 9h
    // (Full-Day Permission, days=1.0), the system automatically opens (and
    // keeps in sync) ONE leave record per employee per month here — see
    // com.example.backend.service.PermissionSurplusService. Only APPROVED
    // permission requests count toward this total. These three fields are
    // never set by the client.

    // True only for these system-generated records, so the UI can label
    // them distinctly ("Half-Day Permission" / "Full-Day Permission")
    // instead of showing them as an ordinary employee-submitted leave.
    private Boolean surplusPermission = false;

    // "yyyy-MM" of the month this surplus leave represents. Doubles as the
    // upsert key so we update/replace the one record for that employee +
    // month instead of creating duplicates as more permission hours come in.
    private String surplusMonth;

    // Total APPROVED permission hours logged for `surplusMonth` at the time
    // this record was last (re)computed — shown to the employee/owner so
    // it's obvious how the Half-Day/Full-Day Permission figure was arrived at.
    private Double surplusHoursSnapshot;
}