package com.example.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.dto.EmployeeDaysAggregate;
import com.example.backend.entity.DateType;
import com.example.backend.entity.LeaveRequest;
import com.example.backend.entity.Status;

public interface LeaveRequestRepository
        extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByEmployeeName(String employeeName);

    List<LeaveRequest> findByStatus(Status status);

    List<LeaveRequest> findByStatusIn(List<Status> statuses);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employeeName = :employeeName AND l.status IN (:statuses)")
    List<LeaveRequest> findByEmployeeNameAndStatuses(
            @Param("employeeName") String employeeName,
            @Param("statuses") List<Status> statuses
    );

    List<LeaveRequest> findByEmployeeNameOrderByCreatedDateDesc(String employeeName);

    // Used to enforce the Short Leave quotas (2 hrs/day, 4 hrs/month) — we
    // pull every Short Leave record for the employee and sum in Java so the
    // rule works identically regardless of the underlying database.
    List<LeaveRequest> findByEmployeeNameAndDateType(String employeeName, DateType dateType);

    // The one auto-generated "surplus allocated hours" leave for a given
    // employee + month (see PermissionSurplusService), if one currently exists.
    java.util.Optional<LeaveRequest> findByEmployeeNameAndSurplusMonth(String employeeName, String surplusMonth);

    // Powers the owner's month-filterable leave report: every leave whose
    // fromDate falls in the given calendar year+month, for one employee.
    @Query("SELECT l FROM LeaveRequest l WHERE l.employeeName = :employeeName " +
           "AND YEAR(l.fromDate) = :year AND MONTH(l.fromDate) = :month")
    List<LeaveRequest> findByEmployeeNameAndMonth(
            @Param("employeeName") String employeeName,
            @Param("year") int year,
            @Param("month") int month
    );

    // ── Aggregated projection ───────────────────────────────────────────
    // Backs the all-employees leave summary (GET /leaves/summary). Instead
    // of looping over every employee and loading/filtering their entire
    // leave history in Java (N+1 and does a lot of unnecessary work), the
    // database groups and sums every employee's APPROVED days for the
    // given calendar year in a single round trip.
    @Query("SELECT l.employeeName AS employeeName, COALESCE(SUM(l.days), 0) AS totalDays " +
           "FROM LeaveRequest l " +
           "WHERE l.status = com.example.backend.entity.Status.APPROVED " +
           "AND YEAR(l.fromDate) = :year " +
           "GROUP BY l.employeeName")
    List<EmployeeDaysAggregate> aggregateApprovedDaysForYear(@Param("year") int year);

    @Modifying
    @Transactional
    void deleteByEmployeeName(String employeeName);
}