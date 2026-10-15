package com.example.backend.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.dto.EmployeeDaysAggregate;
import com.example.backend.dto.LeaveTodayRow;
import com.example.backend.entity.LeaveRequest;
import com.example.backend.entity.Status;

public interface LeaveRequestRepository
        extends JpaRepository<LeaveRequest, Long> {

//     List<LeaveRequest> findByEmployeeName(String employeeName);

//     List<LeaveRequest> findByStatus(Status status);

    List<LeaveRequest> findByStatusIn(List<Status> statuses);

    @Query("SELECT l FROM LeaveRequest l WHERE l.employeeName = :employeeName AND l.status IN (:statuses)")
    List<LeaveRequest> findByEmployeeNameAndStatuses(
            @Param("employeeName") String employeeName,
            @Param("statuses") List<Status> statuses
    );

    List<LeaveRequest> findByEmployeeNameOrderByCreatedDateDesc(String employeeName);

    // Short Leave quotas (2 hrs/day, 4 hrs/month): the database sums the
    // hours of this employee's non-rejected short leaves in [start, end]
    // (excluding the leave being edited) — one scalar instead of loading
    // every short-leave row the employee has ever had and summing in Java.
    @Query("SELECT COALESCE(SUM(l.hours), 0.0) FROM LeaveRequest l " +
           "WHERE l.employeeName = :employeeName " +
           "AND l.dateType = com.example.backend.entity.DateType.SHORT_LEAVE " +
           "AND l.status <> com.example.backend.entity.Status.REJECTED " +
           "AND l.id <> :excludeId " +
           "AND l.fromDate >= :start AND l.fromDate <= :end")
    Double sumShortLeaveHours(
            @Param("employeeName") String employeeName,
            @Param("excludeId") long excludeId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    // The one auto-generated "surplus allocated hours" leave for a given
    // employee + month (see PermissionSurplusService), if one currently exists.
    java.util.Optional<LeaveRequest> findByEmployeeNameAndSurplusMonth(String employeeName, String surplusMonth);

    // Powers the owner's month-filterable leave report: every leave whose
    // fromDate falls in the given calendar year+month, for one employee.
    // Date-range comparison instead of YEAR()/MONTH() so MySQL can use the
    // (employeeName, fromDate) index instead of scanning every row.
    @Query("SELECT l FROM LeaveRequest l WHERE l.employeeName = :employeeName " +
           "AND l.fromDate >= :start AND l.fromDate < :endExclusive")
    List<LeaveRequest> findByEmployeeNameAndFromDateRange(
            @Param("employeeName") String employeeName,
            @Param("start") LocalDate start,
            @Param("endExclusive") LocalDate endExclusive
    );

    default List<LeaveRequest> findByEmployeeNameAndMonth(String employeeName, int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        return findByEmployeeNameAndFromDateRange(employeeName, start, start.plusMonths(1));
    }

    // ── Aggregated projection ───────────────────────────────────────────
    // Backs the all-employees leave summary (GET /leaves/summary). Instead
    // of looping over every employee and loading/filtering their entire
    // leave history in Java (N+1 and does a lot of unnecessary work), the
    // database groups and sums every employee's APPROVED days for the
    // given calendar year in a single round trip.
    @Query("SELECT l.employeeName AS employeeName, COALESCE(SUM(l.days), 0) AS totalDays " +
           "FROM LeaveRequest l " +
           "WHERE l.status = com.example.backend.entity.Status.APPROVED " +
           "AND l.fromDate >= :start AND l.fromDate < :endExclusive " +
           "GROUP BY l.employeeName")
    List<EmployeeDaysAggregate> aggregateApprovedDays(
            @Param("start") LocalDate start,
            @Param("endExclusive") LocalDate endExclusive);

    default List<EmployeeDaysAggregate> aggregateApprovedDaysForYear(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        return aggregateApprovedDays(start, start.plusYears(1));
    }

    // ── "On leave today" ────────────────────────────────────────────────
    // Backs GET /leaves/today. Reads the existing leave_request rows only —
    // no new table. Returns just the few columns the panel needs (interface
    // projection), so entities are never loaded and memory use stays tiny.
    // A leave "covers" the date when fromDate <= date <= toDate (single-day
    // leaves with a null toDate are treated as fromDate == toDate).
    @Query("SELECT l.employeeName AS employeeName, l.leaveType AS leaveType, " +
           "l.dateType AS dateType, l.halfSession AS halfSession, l.hours AS hours, " +
           "l.days AS days, l.fromDate AS fromDate, l.toDate AS toDate, " +
           "l.status AS status, l.surplusPermission AS surplusPermission " +
           "FROM LeaveRequest l " +
           "WHERE l.status IN (:statuses) " +
           "AND l.fromDate <= :date " +
           "AND (l.toDate >= :date OR (l.toDate IS NULL AND l.fromDate = :date)) " +
           "ORDER BY l.employeeName")
    List<LeaveTodayRow> findLeavesCoveringDate(
            @Param("date") LocalDate date,
            @Param("statuses") List<Status> statuses
    );

    @Modifying
    @Transactional
    void deleteByEmployeeName(String employeeName);
}