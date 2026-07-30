package com.example.backend.dto;

import java.time.LocalDate;

/**
 * Spring Data JPA interface-based projection backing
 * {@code EmployeeProfileRepository#findJoinDatesByUsernames}.
 *
 * The backing query is:
 * <pre>
 * SELECT e.username AS username, e.dateOfJoining AS dateOfJoining
 * FROM EmployeeProfile e
 * WHERE e.username IN :usernames
 * </pre>
 *
 * Spring Data matches each accessor below to the corresponding "AS ..."
 * alias in the JPQL SELECT clause — no implementation is written, Spring
 * proxies this interface at runtime.
 *
 * Used by LeaveController.buildLeaveSummaries() (GET /leaves/summary) to
 * batch-resolve every employee's dateOfJoining in one query (needed to
 * work out their annual leave limit via LeavePolicy.leaveLimitFor), instead
 * of loading each EmployeeProfile individually.
 */
public interface EmployeeJoinDateProjection {

    String getUsername();

    // Null-able: an employee profile may not have dateOfJoining set yet.
    LocalDate getDateOfJoining();
}