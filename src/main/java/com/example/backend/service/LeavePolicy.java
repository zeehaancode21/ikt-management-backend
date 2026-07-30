package com.example.backend.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Central place for the "how many annual leave days does this employee get"
 * rule, so it can't drift between the profile endpoints and the leave
 * endpoints.
 *
 * Rule: an employee who has completed 3 full years of service (measured
 * from their dateOfJoining to today) gets the higher SENIOR limit; anyone
 * with less than 3 years gets the lower JUNIOR limit.
 */
public final class LeavePolicy {

    public static final int SENIOR_LEAVE_LIMIT = 24; // 3+ years of service
    public static final int JUNIOR_LEAVE_LIMIT = 18; // less than 3 years of service

    private LeavePolicy() {
    }

    /**
     * @param dateOfJoining the employee's date of joining; if null (not yet
     *                       filled in on the profile) we conservatively fall
     *                       back to the JUNIOR limit.
     */
    public static int leaveLimitFor(LocalDate dateOfJoining) {
        if (dateOfJoining == null) {
            return JUNIOR_LEAVE_LIMIT;
        }
        long yearsOfService = ChronoUnit.YEARS.between(dateOfJoining, LocalDate.now());
        return yearsOfService >= 3 ? SENIOR_LEAVE_LIMIT : JUNIOR_LEAVE_LIMIT;
    }
}