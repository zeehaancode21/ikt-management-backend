package com.example.backend.dto;

/**
 * Spring Data JPA interface-based projection backing
 * {@code PermissionRequestRepository#sumActiveHoursAndCount}.
 *
 * The backing query is:
 * <pre>
 * SELECT COALESCE(SUM(p.hours), 0) AS totalHours, COUNT(p) AS requestCount
 * FROM PermissionRequest p
 * WHERE p.employeeName = :employeeName AND ... AND p.date BETWEEN :start AND :end
 * </pre>
 *
 * Unlike {@link EmployeeHoursAggregate}, this query is scoped to a single
 * employee and has no GROUP BY, so the repository method returns a single
 * instance of this projection (or null if nothing matched) rather than a
 * List. Spring Data matches each accessor below to its "AS ..." alias in
 * the SELECT clause — no implementation is written, Spring proxies this
 * interface at runtime.
 *
 * Used by PermissionController.computeQuota(employeeName) to fetch one
 * employee's current-month active hours + request count in a single query,
 * instead of loading their entire permission history and summing in Java.
 */
public interface HoursCountProjection {

    // COALESCE(SUM(...), 0) — never null, but declared as the wrapper type
    // so callers can still defensively null-check the whole projection.
    Double getTotalHours();

    // COUNT(p) always projects as a Long in JPQL.
    Long getRequestCount();
}