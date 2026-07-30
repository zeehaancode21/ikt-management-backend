package com.example.backend.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.DateType;
import com.example.backend.entity.LeaveRequest;
import com.example.backend.entity.LeaveType;
import com.example.backend.entity.Status;
import com.example.backend.repository.LeaveRequestRepository;
import com.example.backend.repository.PermissionRequestRepository;

/**
 * Keeps the Leave portal in sync with an employee's *approved* Permission-
 * portal hours.
 *
 * An employee may submit as many permission requests as they like in a
 * calendar month — there is no hard cap on applying (see PermissionController,
 * where the old monthly hard cap was removed). What matters for this service
 * is the running total of hours a manager has actually APPROVED for that
 * employee in that month:
 *
 *   • cumulative approved hours &gt;= {@link #HALF_DAY_THRESHOLD_HOURS} (4h) and
 *     &lt; {@link #FULL_DAY_THRESHOLD_HOURS} (9h) → classified "Half-Day
 *     Permission" and mirrored as a 0.5 day (HALF_DAY) leave.
 *   • cumulative approved hours &gt;= {@link #FULL_DAY_THRESHOLD_HOURS} (9h)  →
 *     classified "Full-Day Permission" and mirrored as a 1.0 day (SINGLE) leave.
 *
 * PENDING, REAPPROVAL_PENDING, and REJECTED permission requests never count
 * toward this total — only APPROVED ones do, per the business rule that the
 * cumulative figure must reflect approved permission hours only.
 *
 * Exactly one Leave record per employee per month is used to represent this
 * (keyed by surplusMonth = "yyyy-MM"), so re-crossing a threshold updates the
 * existing record in place instead of creating a duplicate. Its reason and
 * the frontend's badge both label it "Half-Day Permission" / "Full-Day
 * Permission" so it reads as a distinct, clearly-explained entry rather than
 * an ordinary employee-submitted leave, even though it behaves exactly like
 * one (counts toward the leave balance, can be approved/rejected, etc).
 *
 * Because the grouping key is the calendar month itself, the cumulative
 * total naturally resets at the start of every new month — there is nothing
 * to reset explicitly.
 *
 * Once an OWNER/ADMIN/MANAGER has approved or rejected that generated leave,
 * this service stops rewriting it automatically — further growth in that
 * month's approved permission hours (e.g. crossing from half-day to full-day
 * after the half-day entry was already approved) is left for a human to
 * review rather than silently mutating a decision that's already been made.
 * This is also what prevents duplicate leave records once a threshold has
 * already been processed.
 */
@Service
public class PermissionSurplusService {

    public static final double FREE_MONTHLY_HOURS = 4.0;
    public static final double HALF_DAY_THRESHOLD_HOURS = 4.0;
    public static final double FULL_DAY_THRESHOLD_HOURS = 9.0;

    private final PermissionRequestRepository permissionRepo;
    private final LeaveRequestRepository leaveRepo;

    public PermissionSurplusService(PermissionRequestRepository permissionRepo,
                                     LeaveRequestRepository leaveRepo) {
        this.permissionRepo = permissionRepo;
        this.leaveRepo = leaveRepo;
    }

    /**
     * Total APPROVED permission hours an employee has logged in a given
     * month. This — and only this — is the cumulative figure the Half-Day /
     * Full-Day Permission thresholds are measured against; PENDING,
     * REAPPROVAL_PENDING, and REJECTED requests are excluded.
     *
     * Computed as a single SUM(...) query scoped to the month's date range,
     * rather than loading every approved request the employee has ever made
     * and filtering/summing it in Java — this method is called on every
     * permission approve/reject/create, so keeping it a single lightweight
     * aggregate query matters for overall request latency.
     */
    public double approvedHoursFor(String employeeName, YearMonth month) {
        return permissionRepo.sumApprovedHours(employeeName, month.atDay(1), month.atEndOfMonth());
    }

    /**
     * Recomputes and (if needed) creates/updates/removes the one
     * Half-Day/Full-Day Permission leave record for this employee + month.
     * Call this any time a permission request is approved, rejected, or
     * otherwise changes an employee's *approved* hours for that month.
     */
    @Transactional
    public Optional<LeaveRequest> sync(String employeeName, YearMonth month) {
        double totalApprovedHours = approvedHoursFor(employeeName, month);
        String key = month.toString(); // e.g. "2026-07"

        Optional<LeaveRequest> existingOpt = leaveRepo.findByEmployeeNameAndSurplusMonth(employeeName, key);

        // Already decided (or mid change-request) — a human has taken
        // ownership of it, so leave it alone. This is also what stops a
        // duplicate leave record from ever being created for a month whose
        // threshold has already been processed.
        if (existingOpt.isPresent()
                && existingOpt.get().getStatus() != Status.PENDING) {
            return Optional.empty();
        }

        if (totalApprovedHours < HALF_DAY_THRESHOLD_HOURS) {
            // Dropped back under the threshold (e.g. an approved request was
            // later rejected) and nothing has been approved yet on the Leave
            // side — retract the pending auto-generated leave, if any.
            existingOpt.ifPresent(leaveRepo::delete);
            return Optional.empty();
        }

        boolean fullDay = totalApprovedHours >= FULL_DAY_THRESHOLD_HOURS;
        String classification = fullDay ? "Full-Day Permission" : "Half-Day Permission";
        LeaveRequest leave = existingOpt.orElseGet(LeaveRequest::new);

        LocalDate effectiveDate = month.atEndOfMonth();
        leave.setEmployeeName(employeeName);
        leave.setSurplusPermission(true);
        leave.setSurplusMonth(key);
        leave.setSurplusHoursSnapshot(totalApprovedHours);
        leave.setFromDate(effectiveDate);
        leave.setToDate(effectiveDate);
        leave.setDateType(fullDay ? DateType.SINGLE : DateType.HALF_DAY);
        leave.setHalfSession(fullDay ? null : "FIRST_HALF");
        leave.setDays(fullDay ? 1.0 : 0.5);
        leave.setHours(null);
        leave.setLeaveType(leave.getLeaveType() != null ? leave.getLeaveType() : LeaveType.CASUAL);
        leave.setReason(classification + ": " + formatHours(totalApprovedHours)
                + "h of approved permission used in " + month + " — automatically added as "
                + (fullDay ? "a full day" : "a half day") + " of leave because approved permission hours reached "
                + formatHours(fullDay ? FULL_DAY_THRESHOLD_HOURS : HALF_DAY_THRESHOLD_HOURS) + "h/month.");
        leave.setStatus(Status.PENDING);

        return Optional.of(leaveRepo.save(leave));
    }

    private static String formatHours(double hours) {
        double rounded = Math.round(hours * 10) / 10.0;
        return rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }
}