package com.example.backend.dto;

/**
 * Spring Data JPA interface-based projection backing
 * {@code PermissionRequestRepository#aggregateApprovedHours}.
 *
 * The backing query is:
 * <pre>
 * SELECT p.employeeName AS employeeName,
 *        COALESCE(SUM(p.hours), 0) AS approvedHours
 * FROM PermissionRequest p
 * WHERE p.status = APPROVED AND ... GROUP BY p.employeeName
 * </pre>
 *
 * Spring Data matches each accessor below to the corresponding "AS ..."
 * alias in the JPQL SELECT clause — no implementation is written, Spring
 * proxies this interface at runtime.
 *
 * Used alongside EmployeeHoursAggregate by
 * PermissionController.buildQuotaSummaries() to get every employee's
 * current month APPROVED permission hours (the figure that drives the
 * Half-Day/Full-Day Permission auto-classification) in one grouped query.
 *
 * NOTE: this DTO was also missing from the project (imported by both
 * PermissionRequestRepository and PermissionController) — without it
 * neither file compiles, independent of the two DTOs you asked about.
 */
public interface EmployeeApprovedAggregate {

    String getEmployeeName();

    // COALESCE(SUM(...), 0) — never null, but declared as the wrapper type
    // to match how callers null-check it defensively.
    Double getApprovedHours();
}