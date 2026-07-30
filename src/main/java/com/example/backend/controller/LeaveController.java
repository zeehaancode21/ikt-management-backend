package com.example.backend.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.backend.dto.EmployeeDaysAggregate;
import com.example.backend.dto.EmployeeJoinDateProjection;
import com.example.backend.entity.DateType;
import com.example.backend.entity.EmployeeProfile;
import com.example.backend.entity.LeaveRequest;
import com.example.backend.entity.LeaveType;
import com.example.backend.entity.Notification;
import com.example.backend.entity.Status;
import com.example.backend.repository.EmployeeProfileRepository;
import com.example.backend.repository.LeaveRequestRepository;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.FcmService;
import com.example.backend.service.LeavePolicy;

import org.springframework.messaging.simp.SimpMessagingTemplate;

@RestController
@RequestMapping("/leaves")
public class LeaveController {

    // ── Short Leave quota rules ─────────────────────────────────────────
    // "Short Leave" is an hours-based leave (e.g. stepping out for a few
    // hours for an appointment) distinct from Single/Range/Half Day leaves.
    //   • A single day may carry at most MAX_HOURS_PER_DAY of short leave.
    //     If a request would push that day's total above the cap, it is
    //     automatically converted into a HALF_DAY leave instead of being
    //     rejected — the employee still gets time off, just recorded under
    //     the correct bucket.
    //   • Across a calendar month, an employee may take at most
    //     MAX_HOURS_PER_MONTH of short leave in total. Once that's used up,
    //     further short-leave requests are rejected (they can still apply
    //     for Half Day / Single / Range leave as usual).
    private static final double MAX_HOURS_PER_DAY = 2.0;
    private static final double MAX_HOURS_PER_MONTH = 4.0;

    private final LeaveRequestRepository repo;
    private final UserRepository userRepo;
    private final EmployeeProfileRepository profileRepo;
    private final NotificationRepository notifRepo;
    private final SimpMessagingTemplate messagingTemplate;
    private final FcmService fcmService;

    public LeaveController(LeaveRequestRepository repo,
                            UserRepository userRepo,
                            EmployeeProfileRepository profileRepo,
                            NotificationRepository notifRepo,
                            SimpMessagingTemplate messagingTemplate,
                            FcmService fcmService) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.profileRepo = profileRepo;
        this.notifRepo = notifRepo;
        this.messagingTemplate = messagingTemplate;
        this.fcmService = fcmService;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ── Leave notifications (mirrors MessageController's message-notify flow) ──
    // Saves a Notification row, pushes it over the /queue/notifications
    // WebSocket topic, and fires an FCM push — same pipeline used for the
    // "someone messaged you" popup, just triggered from leave events instead.
    private void notifyLeaveEvent(String targetUsername, String senderUsername,
                                   String content, String type,
                                   String fcmTitle, String fcmBody) {
        Notification notif = new Notification();
        notif.setTargetUsername(targetUsername);
        notif.setSenderUsername(senderUsername);
        notif.setContent(content);
        notif.setType(type);
        Notification savedNotif = notifRepo.save(notif);

        messagingTemplate.convertAndSendToUser(targetUsername, "/queue/notifications", savedNotif);

        try {
            fcmService.sendNotificationToUser(targetUsername, senderUsername, fcmTitle, fcmBody, null);
        } catch (Exception e) {
            System.err.println("Failed to send FCM leave notification: " + e.getMessage());
        }
    }

    // Notifies every OWNER/ADMIN/MANAGER (except the requester, just in case)
    // that a new leave request needs their attention.
    private void notifyApproversOfNewLeaveRequest(LeaveRequest leave) {
        String requester = leave.getEmployeeName();
        List<String> approvers = userRepo.findUsernamesByRoles(List.of("OWNER", "ADMIN", "MANAGER")).stream()
                .filter(u -> !u.equals(requester))
                .toList();

        String content = requester + " requested " + describeLeave(leave);
        String fcmBody = content.length() > 100 ? content.substring(0, 100) + "..." : content;

        for (String approver : approvers) {
            notifyLeaveEvent(approver, requester, content, "LEAVE_REQUEST", "🗓️ New Leave Request", fcmBody);
        }
    }

    // Notifies the employee once their leave (or change request) has been
    // approved/rejected by an OWNER/ADMIN/MANAGER.
    private void notifyEmployeeOfLeaveDecision(LeaveRequest leave, Status decision) {
        boolean approved = decision == Status.APPROVED;
        String content = "Your leave request (" + describeLeave(leave) + ") was "
                + (approved ? "approved" : "rejected") + ".";
        String title = approved ? "✅ Leave Approved" : "❌ Leave Rejected";

        notifyLeaveEvent(leave.getEmployeeName(), currentUsername(), content,
                approved ? "LEAVE_APPROVED" : "LEAVE_REJECTED", title, content);
    }

    private String describeLeave(LeaveRequest leave) {
        String type = leave.getDateType() != null ? leave.getDateType().toString() : "leave";
        if (leave.getFromDate() == null) {
            return type;
        }
        if (leave.getToDate() != null && !leave.getToDate().equals(leave.getFromDate())) {
            return type + " from " + leave.getFromDate() + " to " + leave.getToDate();
        }
        return type + " on " + leave.getFromDate();
    }

    // ── DTO for the reapproval (change-request) body ────────────────────
    public record ReapprovalRequest(
            LocalDate fromDate,
            LocalDate toDate,
            DateType dateType,
            String halfSession,
            Double hours,
            LeaveType leaveType,
            String reason,
            Double days
    ) {}

    // Wraps the saved leave together with a flag/message telling the client
    // whether their Short Leave request got auto-converted to a Half Day.
    public record LeaveSaveResponse(
            LeaveRequest leave,
            boolean convertedToHalfDay,
            String message
    ) {}

    // Internal result of running the Short Leave rules against a request.
    private record ShortLeaveOutcome(boolean converted, String errorMessage) {}

    // Only OWNER, ADMIN, MANAGER can see pending leaves.
    // Includes both brand-new PENDING requests and REAPPROVAL_PENDING
    // (an already-approved leave that the employee wants to change).
    @GetMapping("/pending")
    public ResponseEntity<?> getAllPending() {
        String role = getUserRole(currentUsername());
        if (!List.of("OWNER", "ADMIN", "MANAGER").contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok(repo.findByStatusIn(List.of(Status.PENDING, Status.REAPPROVAL_PENDING)));
    }

    @PostMapping("/request")
    public ResponseEntity<?> save(@RequestBody LeaveRequest leave) {
        // Always set employee name from the logged-in user — never trust what the client sends
        leave.setEmployeeName(currentUsername());

        boolean convertedToHalfDay = false;
        if (leave.getDateType() == DateType.SHORT_LEAVE) {
            ShortLeaveOutcome outcome = applyShortLeaveRules(leave, null);
            if (outcome.errorMessage() != null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(outcome.errorMessage());
            }
            convertedToHalfDay = outcome.converted();
        }

        LeaveRequest saved = repo.save(leave);
        notifyApproversOfNewLeaveRequest(saved);
        String message = convertedToHalfDay
                ? "Heads up: this exceeded the 2-hour daily short-leave limit, so it was recorded as a Half Day leave instead."
                : null;
        return ResponseEntity.ok(new LeaveSaveResponse(saved, convertedToHalfDay, message));
    }

    @GetMapping("/employee")
    public ResponseEntity<?> getLeavesByEmployee(@RequestParam String employeeName) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok(repo.findByEmployeeNameOrderByCreatedDateDesc(employeeName));
    }

    @GetMapping("/employee-details")
    public ResponseEntity<?> getAllLeavesByEmployee(@RequestParam String employeeName) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok(repo.findByEmployeeNameAndStatuses(
                employeeName,
                List.of(Status.APPROVED, Status.REJECTED, Status.REAPPROVAL_PENDING)
        ));
    }

    // ── Annual leave quota (24 vs 18 days) ──────────────────────────────
    // Derived from the employee's dateOfJoining on their EmployeeProfile:
    //   • 3+ years of service → 24 days/year
    //   • under 1 year        → 18 days/year
    // Same visibility rule as the other per-employee endpoints: the
    // employee themself, or OWNER/ADMIN/MANAGER.
    public record LeaveLimitResponse(int leaveLimit, LocalDate dateOfJoining) {}

    @GetMapping("/leave-limit")
    public ResponseEntity<?> getLeaveLimit(@RequestParam String employeeName) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        LocalDate dateOfJoining = profileRepo.findByUsername(employeeName)
                .map(EmployeeProfile::getDateOfJoining)
                .orElse(null);
        // This value can change any time an employee's profile is edited,
        // so make sure browsers/proxies never serve a stale cached copy.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(new LeaveLimitResponse(LeavePolicy.leaveLimitFor(dateOfJoining), dateOfJoining));
    }

    // ── Owner: all-employees leave summary ───────────────────────────────
    // Powers a single overview page listing every employee's leave days
    // used/remaining for the current calendar year, mirroring
    // PermissionController's /permissions/summary so an owner doesn't have
    // to select employees one at a time to see where everyone stands.
    public record EmployeeLeaveSummary(
            String employeeName,
            double daysUsed,
            double daysRemaining,
            int leaveLimit,
            boolean isOverLimit
    ) {}

    @GetMapping("/summary")
    public ResponseEntity<?> getAllEmployeeSummaries() {
        String role = getUserRole(currentUsername());
        if (!List.of("OWNER", "ADMIN", "MANAGER").contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }

        List<String> employeeNames = userRepo.findUsernamesByRoles(List.of("USER", "LEAD"));
        if (employeeNames.isEmpty()) {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store, no-cache, must-revalidate")
                    .header("Pragma", "no-cache")
                    .body(List.of());
        }

        List<EmployeeLeaveSummary> summaries = buildLeaveSummaries(employeeNames);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(summaries);
    }

    // Builds every employee's leave summary in a fixed, small number of
    // database round trips regardless of headcount — one aggregate query
    // for every employee's APPROVED days this year (grouped at the
    // database), and one batch query for every employee's dateOfJoining
    // (needed to resolve their annual leave limit). Looping per employee
    // and querying their full leave history one at a time would be an N+1
    // pattern that scales linearly with the number of employees; this
    // implementation stays at two queries total no matter how many
    // employees exist.
    private List<EmployeeLeaveSummary> buildLeaveSummaries(List<String> employeeNames) {
        int year = LocalDate.now().getYear();

        Map<String, Double> daysUsedByEmployee = repo.aggregateApprovedDaysForYear(year).stream()
                .collect(Collectors.toMap(EmployeeDaysAggregate::getEmployeeName, EmployeeDaysAggregate::getTotalDays));

        // NOTE: built with a plain loop instead of Collectors.toMap(...) —
        // getDateOfJoining() is nullable (an employee profile may not have
        // it set yet), and Collectors.toMap throws a NullPointerException
        // the moment it encounters a null value. That NPE was bubbling up
        // as a generic 400 Bad Request from GlobalExceptionHandler,
        // breaking this endpoint entirely for any employee list that
        // included a profile without a dateOfJoining.
        Map<String, LocalDate> joinDateByEmployee = new java.util.HashMap<>();
        for (EmployeeJoinDateProjection p : profileRepo.findJoinDatesByUsernames(employeeNames)) {
            joinDateByEmployee.put(p.getUsername(), p.getDateOfJoining());
        }

        return employeeNames.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(name -> {
                    double daysUsed = daysUsedByEmployee.getOrDefault(name, 0.0);
                    int leaveLimit = LeavePolicy.leaveLimitFor(joinDateByEmployee.get(name));
                    double daysRemaining = leaveLimit - daysUsed;
                    boolean isOverLimit = daysUsed > leaveLimit;
                    return new EmployeeLeaveSummary(name, daysUsed, daysRemaining, leaveLimit, isOverLimit);
                })
                .toList();
    }

    // ── Owner: one employee's leave records + counts for one month ──────
    // Mirrors PermissionController's /employee-monthly so the owner can see
    // allowed/rejected/pending leave for a specific employee in a specific
    // calendar month (including any auto-generated surplus-permission leave).
    public record MonthlyCounts(int approved, int rejected, int pending, double approvedDays) {}
    public record EmployeeMonthlyReport(List<LeaveRequest> records, MonthlyCounts counts) {}

    @GetMapping("/employee-monthly")
    public ResponseEntity<?> getEmployeeMonthly(@RequestParam String employeeName, @RequestParam String month) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        java.time.YearMonth ym;
        try {
            ym = java.time.YearMonth.parse(month); // expects "yyyy-MM"
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("month must be in yyyy-MM format.");
        }

        List<LeaveRequest> records = repo.findByEmployeeNameAndMonth(employeeName, ym.getYear(), ym.getMonthValue());

        int approved = 0, rejected = 0, pending = 0;
        double approvedDays = 0;
        for (LeaveRequest l : records) {
            switch (l.getStatus()) {
                case APPROVED -> { approved++; approvedDays += l.getDays(); }
                case REJECTED -> rejected++;
                case PENDING, REAPPROVAL_PENDING -> pending++;
            }
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(new EmployeeMonthlyReport(records, new MonthlyCounts(approved, rejected, pending, approvedDays)));
    }

    private boolean isOwnRecordOrManager(String employeeName) {
        String requester = currentUsername();
        if (requester.equals(employeeName)) {
            return true;
        }
        return List.of("OWNER", "ADMIN", "MANAGER").contains(getUserRole(requester));
    }

    // ── Request a change to an already-approved leave ("reapproval") ────
    // Employee proposes new dates/type/reason for a leave that's still in
    // the future. The original approved data is left untouched; only the
    // pending* fields are populated and the status flips to
    // REAPPROVAL_PENDING so it shows up in the owner's pending queue again.
    @PostMapping("/{leave_request_id}/reapproval")
    public ResponseEntity<?> requestReapproval(
            @PathVariable Long leave_request_id,
            @RequestBody ReapprovalRequest body
    ) {
        String username = currentUsername();

        return repo.findById(leave_request_id).<ResponseEntity<?>>map(leave -> {
            if (!leave.getEmployeeName().equals(username)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You can only request changes to your own leave.");
            }
            if (leave.getStatus() != Status.APPROVED) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Only an approved leave can have a change requested. Current status: " + leave.getStatus());
            }
            if (leave.getFromDate() == null || !leave.getFromDate().isAfter(LocalDate.now())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("This leave has already started or is starting today, so it can no longer be changed.");
            }
            if (body.fromDate() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("A new start date is required.");
            }

            DateType newDateType = body.dateType() != null ? body.dateType() : leave.getDateType();
            LocalDate newFrom = body.fromDate();
            LocalDate newTo = body.toDate() != null ? body.toDate() : body.fromDate();
            String newHalfSession = body.halfSession();
            Double newHours = body.hours();
            Double newDays = body.days();

            boolean convertedToHalfDay = false;
            if (newDateType == DateType.SHORT_LEAVE) {
                // Run the same Short Leave rules against a scratch object,
                // excluding this leave's own id so it doesn't double-count
                // against itself.
                LeaveRequest scratch = new LeaveRequest();
                scratch.setEmployeeName(leave.getEmployeeName());
                scratch.setFromDate(newFrom);
                scratch.setToDate(newTo);
                scratch.setHours(newHours);
                scratch.setHalfSession(newHalfSession);

                ShortLeaveOutcome outcome = applyShortLeaveRules(scratch, leave.getId());
                if (outcome.errorMessage() != null) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(outcome.errorMessage());
                }
                convertedToHalfDay = outcome.converted();
                newDateType = scratch.getDateType();
                newHours = scratch.getHours();
                newHalfSession = scratch.getHalfSession();
                newDays = scratch.getDays();
            }

            leave.setPendingFromDate(newFrom);
            leave.setPendingToDate(newTo);
            leave.setPendingDateType(newDateType);
            leave.setPendingHalfSession(newHalfSession);
            leave.setPendingHours(newHours);
            leave.setPendingLeaveType(body.leaveType() != null ? body.leaveType() : leave.getLeaveType());
            leave.setPendingReason(body.reason() != null && !body.reason().isBlank() ? body.reason() : leave.getReason());
            leave.setPendingDays(newDays);
            leave.setStatus(Status.REAPPROVAL_PENDING);

            LeaveRequest saved = repo.save(leave);
            notifyApproversOfNewLeaveRequest(saved);
            String message = convertedToHalfDay
                    ? "Heads up: this change exceeded the 2-hour daily short-leave limit, so it was recorded as a Half Day leave instead."
                    : null;
            return ResponseEntity.ok(new LeaveSaveResponse(saved, convertedToHalfDay, message));
        }).orElse(ResponseEntity.notFound().build());
    }

    // Employee can withdraw their own pending change request before the
    // owner acts on it — reverts cleanly back to the original approved leave.
    @DeleteMapping("/{leave_request_id}/reapproval")
    public ResponseEntity<?> cancelReapproval(@PathVariable Long leave_request_id) {
        String username = currentUsername();

        return repo.findById(leave_request_id).<ResponseEntity<?>>map(leave -> {
            if (!leave.getEmployeeName().equals(username)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only cancel your own request.");
            }
            if (leave.getStatus() != Status.REAPPROVAL_PENDING) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("There is no pending change to cancel.");
            }
            clearPendingFields(leave);
            leave.setStatus(Status.APPROVED);
            return ResponseEntity.ok(repo.save(leave));
        }).orElse(ResponseEntity.notFound().build());
    }

    // FIX: Only OWNER, ADMIN, MANAGER can approve or reject a leave.
    // Previously ANY logged-in user could approve their own leave by calling this endpoint directly.
    //
    // Also now handles REAPPROVAL_PENDING leaves: approving copies the
    // employee's proposed changes onto the real leave (and the leave stays
    // APPROVED); rejecting simply discards the proposed changes and leaves
    // the original approved leave exactly as it was.
    @PutMapping("/{leave_request_id}/{status}")
    public ResponseEntity<?> updateLeaveRequest(
            @PathVariable Long leave_request_id,
            @PathVariable Status status
    ) {
        String role = getUserRole(currentUsername());
        if (!List.of("OWNER", "ADMIN", "MANAGER").contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Only managers can approve or reject leaves");
        }
        if (status != Status.APPROVED && status != Status.REJECTED) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Status must be APPROVED or REJECTED.");
        }

        repo.findById(leave_request_id).ifPresent(leave -> {
            if (leave.getStatus() == Status.REAPPROVAL_PENDING) {
                if (status == Status.APPROVED) {
                    leave.setFromDate(leave.getPendingFromDate());
                    leave.setToDate(leave.getPendingToDate());
                    if (leave.getPendingDateType() != null) leave.setDateType(leave.getPendingDateType());
                    leave.setHalfSession(leave.getPendingHalfSession());
                    leave.setHours(leave.getPendingHours());
                    if (leave.getPendingLeaveType() != null) leave.setLeaveType(leave.getPendingLeaveType());
                    if (leave.getPendingReason() != null) leave.setReason(leave.getPendingReason());
                    if (leave.getPendingDays() != null) leave.setDays(leave.getPendingDays());
                }
                // Whether the change is approved or rejected, the leave itself
                // goes back to a plain APPROVED state — rejecting a change
                // request never touches the original approved leave.
                clearPendingFields(leave);
                leave.setStatus(Status.APPROVED);
            } else {
                leave.setStatus(status);
            }
            LeaveRequest saved = repo.save(leave);
            notifyEmployeeOfLeaveDecision(saved, status);
        });

        return ResponseEntity.ok().build();
    }

    private void clearPendingFields(LeaveRequest leave) {
        leave.setPendingFromDate(null);
        leave.setPendingToDate(null);
        leave.setPendingDateType(null);
        leave.setPendingHalfSession(null);
        leave.setPendingHours(null);
        leave.setPendingLeaveType(null);
        leave.setPendingReason(null);
        leave.setPendingDays(null);
    }

    private String getUserRole(String username) {
        return userRepo.findByUsername(username)
                .map(u -> u.getRole() != null ? u.getRole() : "USER")
                .orElse("USER");
    }

    // ── Short Leave rule engine ──────────────────────────────────────────
    // Validates/normalizes a SHORT_LEAVE request in-place on `leave`:
    //   • rejects it outright if the basic shape is invalid (no date,
    //     multi-day range, missing/zero hours)
    //   • auto-converts it to HALF_DAY if this request — combined with any
    //     other non-rejected short leave already logged for that same day —
    //     would exceed MAX_HOURS_PER_DAY
    //   • otherwise checks the MAX_HOURS_PER_MONTH cap and rejects with a
    //     clear message if it would be exceeded
    // `excludeId` should be the id of the leave being edited (reapproval
    // flow) so it doesn't count against itself, or null for a brand-new
    // request.
    private ShortLeaveOutcome applyShortLeaveRules(LeaveRequest leave, Long excludeId) {
        if (leave.getFromDate() == null) {
            return new ShortLeaveOutcome(false, "A date is required for short leave.");
        }
        if (leave.getToDate() == null) {
            leave.setToDate(leave.getFromDate());
        }
        if (!leave.getFromDate().equals(leave.getToDate())) {
            return new ShortLeaveOutcome(false, "Short leave can only be requested for a single day.");
        }

        Double requested = leave.getHours();
        if (requested == null || requested <= 0) {
            return new ShortLeaveOutcome(false, "Please specify the number of hours (greater than 0) for short leave.");
        }

        double existingForDay = sumShortLeaveHours(leave.getEmployeeName(), excludeId,
                l -> leave.getFromDate().equals(l.getFromDate()));

        if (requested > MAX_HOURS_PER_DAY || (existingForDay + requested) > MAX_HOURS_PER_DAY) {
            // Daily cap breached (either by this request alone, or combined
            // with short leave already taken/pending that same day) —
            // convert to a Half Day instead of rejecting.
            leave.setDateType(DateType.HALF_DAY);
            leave.setHours(null);
            leave.setDays(0.5);
            if (leave.getHalfSession() == null || leave.getHalfSession().isBlank()) {
                leave.setHalfSession("FIRST_HALF");
            }
            return new ShortLeaveOutcome(true, null);
        }

        double existingForMonth = sumShortLeaveHours(leave.getEmployeeName(), excludeId,
                l -> l.getFromDate() != null
                        && l.getFromDate().getYear() == leave.getFromDate().getYear()
                        && l.getFromDate().getMonthValue() == leave.getFromDate().getMonthValue());

        if (existingForMonth + requested > MAX_HOURS_PER_MONTH) {
            double remaining = Math.max(0, MAX_HOURS_PER_MONTH - existingForMonth);
            return new ShortLeaveOutcome(false, String.format(
                    "You've already used %.1f of your %.1f short-leave hours for this month ",
                    existingForMonth, MAX_HOURS_PER_MONTH, remaining));
        }

        // Short leave doesn't consume the day-based annual leave pool.
        leave.setDays(0.0);
        return new ShortLeaveOutcome(false, null);
    }

    private double sumShortLeaveHours(String employeeName, Long excludeId, Predicate<LeaveRequest> matcher) {
        return repo.findByEmployeeNameAndDateType(employeeName, DateType.SHORT_LEAVE).stream()
                .filter(l -> l.getStatus() != Status.REJECTED)
                .filter(l -> excludeId == null || !excludeId.equals(l.getId()))
                .filter(matcher)
                .mapToDouble(l -> l.getHours() != null ? l.getHours() : 0.0)
                .sum();
    }
}