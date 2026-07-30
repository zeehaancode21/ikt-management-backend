package com.example.backend.controller;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import com.example.backend.dto.EmployeeApprovedAggregate;
import com.example.backend.dto.EmployeeHoursAggregate;
import com.example.backend.dto.HoursCountProjection;
import com.example.backend.entity.Notification;
import com.example.backend.entity.PermissionRequest;
import com.example.backend.entity.PermissionType;
import com.example.backend.entity.Status;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.PermissionRequestRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.FcmService;
import com.example.backend.service.PermissionSurplusService;

import org.springframework.messaging.simp.SimpMessagingTemplate;

@RestController
@RequestMapping("/permissions")
public class PermissionController {

    // ── Permission quota rules ──────────────────────────────────────────
    // "Permission" is hours-based time away for part of a day (e.g. a
    // doctor's appointment), distinct from the day-based Leave pool.
    //   • A single request — and the running total for that calendar day —
    //     may not exceed MAX_HOURS_PER_DAY. This is a hard cap.
    //   • MAX_HOURS_PER_MONTH is the *free* monthly allowance, not a hard
    //     cap: an employee can keep requesting permission without any
    //     restriction. Once their APPROVED hours for the month reach
    //     MAX_HOURS_PER_MONTH (4h), PermissionSurplusService automatically
    //     opens a matching "Half-Day Permission" leave on the Leave portal;
    //     if it reaches PermissionSurplusService.FULL_DAY_THRESHOLD_HOURS
    //     (9h) that leave is upgraded to "Full-Day Permission". Only
    //     APPROVED requests count toward this total — PENDING and REJECTED
    //     requests never do. See PermissionSurplusService for the full rule
    //     and how it's kept in sync.
    //   • Across a calendar month, an employee may submit at most
    //     MAX_REQUESTS_PER_MONTH permission requests.
    // Rejected requests never count against any of these caps.
    private static final double MAX_HOURS_PER_DAY = 2.0;
    private static final double MAX_HOURS_PER_MONTH = PermissionSurplusService.FREE_MONTHLY_HOURS;
    private static final int MAX_REQUESTS_PER_MONTH = 12;

    private final PermissionRequestRepository repo;
    private final UserRepository userRepo;
    private final NotificationRepository notifRepo;
    private final SimpMessagingTemplate messagingTemplate;
    private final FcmService fcmService;
    private final PermissionSurplusService surplusService;

    public PermissionController(PermissionRequestRepository repo,
                                 UserRepository userRepo,
                                 NotificationRepository notifRepo,
                                 SimpMessagingTemplate messagingTemplate,
                                 FcmService fcmService,
                                 PermissionSurplusService surplusService) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.notifRepo = notifRepo;
        this.messagingTemplate = messagingTemplate;
        this.fcmService = fcmService;
        this.surplusService = surplusService;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private String getUserRole(String username) {
        return userRepo.findByUsername(username)
                .map(u -> u.getRole() != null ? u.getRole() : "USER")
                .orElse("USER");
    }

    private boolean isManager(String username) {
        return List.of("OWNER", "ADMIN", "MANAGER").contains(getUserRole(username));
    }

    private boolean isOwnRecordOrManager(String employeeName) {
        String requester = currentUsername();
        if (requester.equals(employeeName)) {
            return true;
        }
        return isManager(requester);
    }

    // ── Notifications (mirrors LeaveController's flow) ──────────────────
    private void notifyPermissionEvent(String targetUsername, String senderUsername,
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
            System.err.println("Failed to send FCM permission notification: " + e.getMessage());
        }
    }

    private void notifyApproversOfNewRequest(PermissionRequest p) {
        String requester = p.getEmployeeName();
        List<String> approvers = userRepo.findUsernamesByRoles(List.of("OWNER", "ADMIN", "MANAGER")).stream()
                .filter(u -> !u.equals(requester))
                .toList();

        String content = requester + " requested permission on " + p.getDate() + " (" + formatHours(p.getHours()) + "h)";
        String fcmBody = content.length() > 100 ? content.substring(0, 100) + "..." : content;

        for (String approver : approvers) {
            notifyPermissionEvent(approver, requester, content, "PERMISSION_REQUEST", "\u23F1\uFE0F New Permission Request", fcmBody);
        }
    }

    private void notifyEmployeeOfDecision(PermissionRequest p, Status decision) {
        boolean approved = decision == Status.APPROVED;
        String content = "Your permission request on " + p.getDate() + " was " + (approved ? "approved" : "rejected") + ".";
        String title = approved ? "\u2705 Permission Approved" : "\u274C Permission Rejected";

        notifyPermissionEvent(p.getEmployeeName(), currentUsername(), content,
                approved ? "PERMISSION_APPROVED" : "PERMISSION_REJECTED", title, content);
    }

    private static String formatHours(Double hours) {
        if (hours == null) return "0";
        double rounded = Math.round(hours * 10) / 10.0;
        return rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public record PermissionRequestBody(
            PermissionType permissionType,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            String reason
    ) {}

    public record QuotaResponse(
            double maxHoursPerDay,
            double maxHoursPerMonth,
            int maxRequestsPerMonth,
            double hoursUsedThisMonth,
            double hoursRemainingThisMonth,
            int requestsUsedThisMonth,
            int requestsRemainingThisMonth,
            double approvedHoursThisMonth,
            double surplusHoursThisMonth,
            double surplusLeaveDays,
            double fullDayThresholdHours
    ) {}

    public record EmployeeQuotaSummary(
            String employeeName,
            QuotaResponse quota
    ) {}

    // ── Owner: pending queue ─────────────────────────────────────────────
    // Includes both brand-new PENDING requests and REAPPROVAL_PENDING (an
    // already-approved permission the employee wants to change).
    @GetMapping("/pending")
    public ResponseEntity<?> getAllPending() {
        if (!isManager(currentUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok(repo.findByStatusInOrderByCreatedDateAsc(
                List.of(Status.PENDING, Status.REAPPROVAL_PENDING)));
    }

    // ── Owner: all-employees quota summary ───────────────────────────────
    // Powers a single overview page listing every employee's permission
    // hours used/remaining this month, so an owner doesn't have to select
    // employees one at a time to see where everyone stands.
    @GetMapping("/summary")
    public ResponseEntity<?> getAllEmployeeSummaries() {
        if (!isManager(currentUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        List<String> employeeNames = userRepo.findUsernamesByRoles(List.of("USER", "LEAD"));
        List<EmployeeQuotaSummary> summaries = buildQuotaSummaries(employeeNames);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(summaries);
    }

    // Builds every employee's quota in a fixed, small number of database
    // round trips regardless of headcount.
    //
    // The previous implementation called computeQuota(name) per employee,
    // and each of those calls loaded that employee's *entire* permission
    // history (active + approved) into memory just to filter/sum it in
    // Java — an N+1 query pattern where response time scaled linearly with
    // the number of employees. Here the database groups and sums every
    // employee's current-month rows in two queries total, and the results
    // are joined against the employee list in memory (O(n) map lookups,
    // no further I/O).
    private List<EmployeeQuotaSummary> buildQuotaSummaries(List<String> employeeNames) {
        if (employeeNames.isEmpty()) {
            return List.of();
        }

        YearMonth month = YearMonth.now();
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        Map<String, EmployeeHoursAggregate> activeByEmployee = repo.aggregateActiveHours(start, end).stream()
                .collect(Collectors.toMap(EmployeeHoursAggregate::getEmployeeName, a -> a));
        Map<String, Double> approvedByEmployee = repo.aggregateApprovedHours(start, end).stream()
                .collect(Collectors.toMap(EmployeeApprovedAggregate::getEmployeeName,
                        EmployeeApprovedAggregate::getApprovedHours));

        return employeeNames.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(name -> {
                    EmployeeHoursAggregate active = activeByEmployee.get(name);
                    double hoursUsed = active != null && active.getTotalHours() != null ? active.getTotalHours() : 0.0;
                    int requestsUsed = active != null && active.getRequestCount() != null
                            ? active.getRequestCount().intValue() : 0;
                    double approvedHours = approvedByEmployee.getOrDefault(name, 0.0);
                    return new EmployeeQuotaSummary(name, buildQuota(hoursUsed, requestsUsed, approvedHours));
                })
                .toList();
    }

    // ── Create a new permission request ──────────────────────────────────
    @PostMapping("/request")
    public ResponseEntity<?> save(@RequestBody PermissionRequestBody body) {
        String username = currentUsername();

        // if (body.date() == null) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("A date is required.");
        // }
        // if (body.startTime() == null || body.endTime() == null) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Both a start and end time are required.");
        // }
        // if (body.reason() == null || body.reason().trim().length() < 10) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Please provide a reason of at least 10 characters.");
        // }

        double hours = computeHours(body.startTime(), body.endTime());
        // if (hours <= 0) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("End time must be after start time.");
        // }
        // if (hours > MAX_HOURS_PER_DAY) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        //             .body("Permission is capped at " + formatHours(MAX_HOURS_PER_DAY) + " hours a day.");
        // }

        // String quotaError = checkQuota(username, body.date(), hours, null);
        // if (quotaError != null) {
        //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(quotaError);
        // }

        PermissionRequest p = new PermissionRequest();
        p.setEmployeeName(username);
        p.setPermissionType(body.permissionType() != null ? body.permissionType() : PermissionType.OTHER);
        p.setDate(body.date());
        p.setStartTime(body.startTime());
        p.setEndTime(body.endTime());
        p.setHours(hours);
        p.setReason(body.reason().trim());
        p.setStatus(Status.PENDING);

        PermissionRequest saved = repo.save(p);
        notifyApproversOfNewRequest(saved);
        syncSurplusAndNotify(username, YearMonth.from(saved.getDate()));
        return ResponseEntity.ok(saved);
    }

    // ── Employee's own requests ───────────────────────────────────────────
    @GetMapping("/employee")
    public ResponseEntity<?> getByEmployee(@RequestParam String employeeName) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok(repo.findByEmployeeNameOrderByDateDesc(employeeName));
    }

    // ── Owner: full history for one employee ─────────────────────────────
    @GetMapping("/employee-details")
    public ResponseEntity<?> getEmployeeDetails(@RequestParam String employeeName) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok(repo.findByEmployeeNameOrderByDateDesc(employeeName));
    }

    // ── Owner: one employee's permission records + counts for one month ──
    // Powers a month picker in the Employee History tab: "how many
    // permission requests were approved / rejected / are pending for this
    // employee in July 2026", plus the underlying records for that month.
    public record MonthlyCounts(int approved, int rejected, int pending, double approvedHours) {}
    public record EmployeeMonthlyReport(List<PermissionRequest> records, MonthlyCounts counts) {}

    @GetMapping("/employee-monthly")
    public ResponseEntity<?> getEmployeeMonthly(@RequestParam String employeeName, @RequestParam String month) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(month); // expects "yyyy-MM"
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("month must be in yyyy-MM format.");
        }

        List<PermissionRequest> records = repo.findByEmployeeNameOrderByDateDesc(employeeName).stream()
                .filter(p -> p.getDate() != null && YearMonth.from(p.getDate()).equals(ym))
                .toList();

        int approved = 0, rejected = 0, pending = 0;
        double approvedHours = 0;
        for (PermissionRequest p : records) {
            switch (p.getStatus()) {
                case APPROVED -> { approved++; approvedHours += p.getHours() != null ? p.getHours() : 0; }
                case REJECTED -> rejected++;
                case PENDING, REAPPROVAL_PENDING -> pending++;
            }
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(new EmployeeMonthlyReport(records, new MonthlyCounts(approved, rejected, pending, approvedHours)));
    }

    // ── Quota for one employee, current month ─────────────────────────────
    @GetMapping("/quota")
    public ResponseEntity<?> getQuota(@RequestParam String employeeName) {
        if (!isOwnRecordOrManager(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(computeQuota(employeeName));
    }

    // ── Request a change to an already-approved permission ("reapproval") ─
    // Employee proposes new details for a permission that's still in the
    // future. The original approved data is left untouched; only the
    // pending* fields are populated and the status flips to
    // REAPPROVAL_PENDING so it shows up in the owner's pending queue again.
    @PostMapping("/{id}/reapproval")
    public ResponseEntity<?> requestReapproval(@PathVariable Long id, @RequestBody PermissionRequestBody body) {
        String username = currentUsername();

        return repo.findById(id).<ResponseEntity<?>>map(p -> {
            if (!p.getEmployeeName().equals(username)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You can only request changes to your own permission request.");
            }
            if (p.getStatus() != Status.APPROVED) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Only an approved permission request can have a change requested. Current status: " + p.getStatus());
            }
            if (p.getDate() == null || !p.getDate().isAfter(LocalDate.now())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("This permission has already started or is starting today, so it can no longer be changed.");
            }
            if (body.date() == null || body.startTime() == null || body.endTime() == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("A new date, start time, and end time are required.");
            }
            if (body.reason() == null || body.reason().trim().length() < 10) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Please provide a reason of at least 10 characters.");
            }

            double newHours = computeHours(body.startTime(), body.endTime());
            if (newHours <= 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("End time must be after start time.");
            }
            if (newHours > MAX_HOURS_PER_DAY) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Permission is capped at " + formatHours(MAX_HOURS_PER_DAY) + " hours a day.");
            }

            String quotaError = checkQuota(username, body.date(), newHours, id);
            if (quotaError != null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(quotaError);
            }

            p.setPendingDate(body.date());
            p.setPendingStartTime(body.startTime());
            p.setPendingEndTime(body.endTime());
            p.setPendingHours(newHours);
            p.setPendingPermissionType(body.permissionType() != null ? body.permissionType() : p.getPermissionType());
            p.setPendingReason(body.reason().trim());
            p.setStatus(Status.REAPPROVAL_PENDING);

            PermissionRequest saved = repo.save(p);
            notifyApproversOfNewRequest(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    // Employee can withdraw their own pending change request before the
    // owner acts on it — reverts cleanly back to the original approved permission.
    @DeleteMapping("/{id}/reapproval")
    public ResponseEntity<?> cancelReapproval(@PathVariable Long id) {
        String username = currentUsername();

        return repo.findById(id).<ResponseEntity<?>>map(p -> {
            if (!p.getEmployeeName().equals(username)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only cancel your own request.");
            }
            if (p.getStatus() != Status.REAPPROVAL_PENDING) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("There is no pending change to cancel.");
            }
            clearPendingFields(p);
            p.setStatus(Status.APPROVED);
            return ResponseEntity.ok(repo.save(p));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Owner: approve/reject ─────────────────────────────────────────────
    // Also handles REAPPROVAL_PENDING requests: approving copies the
    // employee's proposed changes onto the real request (and it stays
    // APPROVED); rejecting simply discards the proposed changes and leaves
    // the original approved request exactly as it was.
    @PutMapping("/{id}/{status}")
    public ResponseEntity<?> updatePermissionRequest(@PathVariable Long id, @PathVariable Status status) {
        String role = getUserRole(currentUsername());
        if (!List.of("OWNER", "ADMIN", "MANAGER").contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Only managers can approve or reject permission requests");
        }
        if (status != Status.APPROVED && status != Status.REJECTED) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Status must be APPROVED or REJECTED.");
        }

        repo.findById(id).ifPresent(p -> {
            LocalDate originalDate = p.getDate();
            boolean wasReapproval = p.getStatus() == Status.REAPPROVAL_PENDING;

            if (wasReapproval) {
                if (status == Status.APPROVED) {
                    p.setDate(p.getPendingDate());
                    p.setStartTime(p.getPendingStartTime());
                    p.setEndTime(p.getPendingEndTime());
                    p.setHours(p.getPendingHours());
                    if (p.getPendingPermissionType() != null) p.setPermissionType(p.getPendingPermissionType());
                    if (p.getPendingReason() != null) p.setReason(p.getPendingReason());
                }
                // Whether the change is approved or rejected, the request
                // itself goes back to a plain APPROVED state — rejecting a
                // change request never touches the original approved request.
                clearPendingFields(p);
                p.setStatus(Status.APPROVED);
            } else {
                p.setStatus(status);
            }
            PermissionRequest saved = repo.save(p);
            notifyEmployeeOfDecision(saved, status);

            // Rejecting a plain request removes hours from the month's
            // active total; approving a reapproval can move hours to a new
            // date/month. Either way, keep the surplus leave in sync.
            if (originalDate != null) {
                syncSurplusAndNotify(saved.getEmployeeName(), YearMonth.from(originalDate));
            }
            if (saved.getDate() != null && !saved.getDate().equals(originalDate)) {
                syncSurplusAndNotify(saved.getEmployeeName(), YearMonth.from(saved.getDate()));
            }
        });

        return ResponseEntity.ok().build();
    }

    // Re-syncs the surplus leave for an employee + month, and — only when
    // that sync actually (re)opened a PENDING surplus leave — notifies the
    // approvers the same way a normal new leave request would.
   private void syncSurplusAndNotify(String employeeName, YearMonth month) {
    surplusService.sync(employeeName, month).ifPresent(leave -> {
        if (leave.getStatus() == Status.PENDING) {
            List<String> approvers = userRepo.findUsernamesByRoles(List.of("OWNER", "ADMIN", "MANAGER")).stream()
                    .filter(u -> !u.equals(employeeName))
                    .toList();
            // FIX: getDays() returns primitive double, no null check needed
            boolean fullDay = leave.getDays() >= 1.0;
            // boolean fullDay = leave.getDays() != null && leave.getDays() >= 1.0;
            String content = employeeName + "'s approved permission hours for " + month
                    + " reached the " + (fullDay ? "Full-Day Permission" : "Half-Day Permission")
                    + " threshold — a matching leave was opened for review.";
            String fcmBody = content.length() > 100 ? content.substring(0, 100) + "..." : content;
            for (String approver : approvers) {
                notifyPermissionEvent(approver, employeeName, content, "LEAVE_REQUEST",
                        fullDay ? "\u2696\uFE0F Full-Day Permission" : "\u2696\uFE0F Half-Day Permission", fcmBody);
            }
        }
    });
}

    private void clearPendingFields(PermissionRequest p) {
        p.setPendingDate(null);
        p.setPendingStartTime(null);
        p.setPendingEndTime(null);
        p.setPendingHours(null);
        p.setPendingPermissionType(null);
        p.setPendingReason(null);
    }

    // ── Quota engine ───────────────────────────────────────────────────────

    // Minutes between start/end, converted to hours and rounded to the
    // nearest quarter-hour — mirrors the frontend's live preview so the
    // number an employee sees while filling the form matches what gets saved.
    private double computeHours(LocalTime start, LocalTime end) {
        long minutes = ChronoUnit.MINUTES.between(start, end);
        if (minutes <= 0) return 0;
        return Math.round((minutes / 60.0) * 4) / 4.0;
    }

    // Returns an error message if adding `requestedHours` on `date` would
    // breach the daily/monthly/requests-per-month caps, or null if it's fine.
    // `excludeId` should be the id of the request being edited (reapproval
    // flow) so it doesn't count against itself, or null for a brand-new request.
    private String checkQuota(String employeeName, LocalDate date, double requestedHours, Long excludeId) {
        List<PermissionRequest> active = repo.findActiveByEmployeeName(employeeName).stream()
                .filter(p -> excludeId == null || !excludeId.equals(p.getId()))
                .toList();

        double existingForDay = active.stream()
                .filter(p -> date.equals(p.getDate()))
                .mapToDouble(p -> p.getHours() != null ? p.getHours() : 0.0)
                .sum();
        if (existingForDay + requestedHours > MAX_HOURS_PER_DAY) {
            return "This would put you over the daily permission limit of " + formatHours(MAX_HOURS_PER_DAY)
                    + " hours for " + date + ".";
        }

        YearMonth month = YearMonth.from(date);
        List<PermissionRequest> forMonth = active.stream()
                .filter(p -> p.getDate() != null && YearMonth.from(p.getDate()).equals(month))
                .toList();

        // NOTE: going past MAX_HOURS_PER_MONTH is intentionally allowed —
        // it never blocks or restricts submitting a request. Once a
        // request is APPROVED, PermissionSurplusService.sync(...) picks up
        // the new cumulative *approved* total and opens/updates a matching
        // "Half-Day Permission" (4h) / "Full-Day Permission" (9h) leave on
        // the Leave portal. Only the daily cap above and the
        // requests-per-month cap below still hard-block, and neither is
        // related to this monthly-hours threshold.

        if (forMonth.size() + 1 > MAX_REQUESTS_PER_MONTH) {
            return "You've already reached the limit of " + MAX_REQUESTS_PER_MONTH + " permission requests for this month.";
        }

        return null;
    }

    // Builds the same Quota shape the frontend renders for both the
    // employee's own quota card and the owner's per-employee view.
    //
    // Uses two bounded, DB-side aggregate queries (current month only)
    // instead of loading the employee's entire permission history and
    // filtering/summing it in Java.
    private QuotaResponse computeQuota(String employeeName) {
        YearMonth month = YearMonth.now();
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        HoursCountProjection active = repo.sumActiveHoursAndCount(employeeName, start, end);
        double hoursUsed = active != null && active.getTotalHours() != null ? active.getTotalHours() : 0.0;
        int requestsUsed = active != null && active.getRequestCount() != null
                ? active.getRequestCount().intValue() : 0;

        // Use Optional to safely handle null from approvedHoursFor
        double approvedHours = Optional.ofNullable(surplusService.approvedHoursFor(employeeName, month))
                .orElse(0.0);

        return buildQuota(hoursUsed, requestsUsed, approvedHours);
    }

    // Shared quota-shaping logic used by both the single-employee quota
    // lookup and the all-employees summary, given already-aggregated
    // hours/request/approved-hours figures for the current month.
    private QuotaResponse buildQuota(double hoursUsed, int requestsUsed, double approvedHours) {
        double surplusHours = Math.max(0, approvedHours - MAX_HOURS_PER_MONTH);
        double surplusLeaveDays = approvedHours >= PermissionSurplusService.FULL_DAY_THRESHOLD_HOURS ? 1.0
                : approvedHours >= PermissionSurplusService.HALF_DAY_THRESHOLD_HOURS ? 0.5
                : 0.0;

        return new QuotaResponse(
                MAX_HOURS_PER_DAY,
                MAX_HOURS_PER_MONTH,
                MAX_REQUESTS_PER_MONTH,
                hoursUsed,
                Math.max(0, MAX_HOURS_PER_MONTH - hoursUsed),
                requestsUsed,
                Math.max(0, MAX_REQUESTS_PER_MONTH - requestsUsed),
                approvedHours,
                surplusHours,
                surplusLeaveDays,
                PermissionSurplusService.FULL_DAY_THRESHOLD_HOURS
        );
    }
}