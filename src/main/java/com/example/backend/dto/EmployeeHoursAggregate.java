package com.example.backend.dto;

/**
 * Spring Data JPA interface-based projection backing
 * {@code PermissionRequestRepository#aggregateActiveHours}.
 *
 * The backing query is:
 * <pre>
 * SELECT p.employeeName AS employeeName,
 *        COALESCE(SUM(p.hours), 0) AS totalHours,
 *        COUNT(p) AS requestCount
 * FROM PermissionRequest p
 * WHERE ... GROUP BY p.employeeName
 * </pre>
 *
 * Spring Data matches each accessor below to the corresponding "AS ..."
 * alias in the JPQL SELECT clause (by name), so the method names here must
 * line up exactly with those aliases — no implementation is written or
 * needed, Spring proxies this interface at runtime.
 *
 * Used by PermissionController.buildQuotaSummaries() to compute, for every
 * employee in a single grouped query, the current month's total
 * (non-rejected) permission hours and how many requests made up that total.
 */
public interface EmployeeHoursAggregate {

    String getEmployeeName();

    // COALESCE(SUM(...), 0) — never null, but declared as the wrapper type
    // to match the other projections/consumers, which null-check defensively.
    Double getTotalHours();

    // COUNT(p) always projects as a Long in JPQL.
    Long getRequestCount();
}