package com.example.backend.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.dto.EmployeeApprovedAggregate;
import com.example.backend.dto.EmployeeHoursAggregate;
import com.example.backend.dto.HoursCountProjection;
import com.example.backend.entity.PermissionRequest;
import com.example.backend.entity.Status;

public interface PermissionRequestRepository extends JpaRepository<PermissionRequest, Long> {

    // Employee's own history, most recent first.
    List<PermissionRequest> findByEmployeeNameOrderByDateDesc(String employeeName);

    // Owner's "pending" queue — brand-new PENDING requests as well as
    // REAPPROVAL_PENDING (an already-approved permission the employee
    // wants to change), across every employee.
    List<PermissionRequest> findByStatusInOrderByCreatedDateAsc(List<Status> statuses);

    // Used to enforce the daily/monthly/requests-per-month quotas when a
    // request is created or reapproved — we pull every non-rejected record
    // for the employee and sum in Java so the rule works identically
    // regardless of the underlying database.
    @Query("SELECT p FROM PermissionRequest p WHERE p.employeeName = :employeeName AND p.status <> com.example.backend.entity.Status.REJECTED")
    List<PermissionRequest> findActiveByEmployeeName(@Param("employeeName") String employeeName);

    // Used by PermissionSurplusService to compute the cumulative *approved*
    // permission hours for a calendar month — the figure that drives the
    // Half-Day/Full-Day Permission auto-classification. Only APPROVED
    // requests count; PENDING, REAPPROVAL_PENDING, and REJECTED requests are
    // excluded on purpose so an employee's Leave-portal entry never reflects
    // hours a manager hasn't actually signed off on yet.
    @Query("SELECT p FROM PermissionRequest p WHERE p.employeeName = :employeeName AND p.status = com.example.backend.entity.Status.APPROVED")
    List<PermissionRequest> findApprovedByEmployeeName(@Param("employeeName") String employeeName);

    // ── Aggregated projections ──────────────────────────────────────────
    // The two queries below back the all-employees quota summary
    // (GET /permissions/summary). Instead of looping over every employee
    // and issuing a query (or two) per employee — an N+1 pattern that made
    // the endpoint scale linearly with headcount — the database groups and
    // sums every employee's rows in a single round trip each, for a fixed
    // total of two queries no matter how many employees exist.
    //
    // Both are scoped to an explicit [start, end] date range (the calendar
    // month being reported on) so the DB can use an index on
    // (employeeName, status, date) rather than scanning full history.

    @Query("SELECT p.employeeName AS employeeName, COALESCE(SUM(p.hours), 0) AS totalHours, COUNT(p) AS requestCount " +
           "FROM PermissionRequest p " +
           "WHERE p.status <> com.example.backend.entity.Status.REJECTED " +
           "AND p.date BETWEEN :start AND :end " +
           "GROUP BY p.employeeName")
    List<EmployeeHoursAggregate> aggregateActiveHours(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT p.employeeName AS employeeName, COALESCE(SUM(p.hours), 0) AS approvedHours " +
           "FROM PermissionRequest p " +
           "WHERE p.status = com.example.backend.entity.Status.APPROVED " +
           "AND p.date BETWEEN :start AND :end " +
           "GROUP BY p.employeeName")
    List<EmployeeApprovedAggregate> aggregateApprovedHours(@Param("start") LocalDate start, @Param("end") LocalDate end);

    // Single-employee equivalent of aggregateActiveHours, used by the
    // per-employee quota endpoint so it doesn't have to pull an employee's
    // entire permission history just to sum one month of it.
    @Query("SELECT COALESCE(SUM(p.hours), 0) AS totalHours, COUNT(p) AS requestCount " +
           "FROM PermissionRequest p " +
           "WHERE p.employeeName = :employeeName AND p.status <> com.example.backend.entity.Status.REJECTED " +
           "AND p.date BETWEEN :start AND :end")
    HoursCountProjection sumActiveHoursAndCount(@Param("employeeName") String employeeName,
                                                 @Param("start") LocalDate start,
                                                 @Param("end") LocalDate end);

    // Single-employee, single-range APPROVED hours total computed at the
    // database — used by PermissionSurplusService instead of loading every
    // approved request the employee has ever made and filtering in Java.
    @Query("SELECT COALESCE(SUM(p.hours), 0) FROM PermissionRequest p " +
           "WHERE p.employeeName = :employeeName AND p.status = com.example.backend.entity.Status.APPROVED " +
           "AND p.date BETWEEN :start AND :end")
    double sumApprovedHours(@Param("employeeName") String employeeName,
                             @Param("start") LocalDate start,
                             @Param("end") LocalDate end);

    @Modifying
    @Transactional
    void deleteByEmployeeName(String employeeName);
}