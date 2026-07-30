package com.example.backend.dto;

/**
 * Spring Data JPA interface-based projection backing
 * {@code LeaveRequestRepository#aggregateApprovedDaysForYear}.
 *
 * The backing query is:
 * <pre>
 * SELECT l.employeeName AS employeeName,
 *        COALESCE(SUM(l.days), 0) AS totalDays
 * FROM LeaveRequest l
 * WHERE l.status = APPROVED AND YEAR(l.fromDate) = :year
 * GROUP BY l.employeeName
 * </pre>
 *
 * Spring Data matches each accessor below to the corresponding "AS ..."
 * alias in the JPQL SELECT clause — no implementation is written, Spring
 * proxies this interface at runtime.
 *
 * Used by LeaveController.buildLeaveSummaries() (GET /leaves/summary) to
 * get every employee's total APPROVED leave days for the current year in
 * a single grouped query, instead of loading each employee's full leave
 * history one at a time.
 */
public interface EmployeeDaysAggregate {

    String getEmployeeName();

    // COALESCE(SUM(...), 0) — never null, but declared as the wrapper type
    // so callers can still defensively null-check it (LeaveController does).
    Double getTotalDays();
}