package com.example.backend.controller;

import com.example.backend.entity.WeekendAttendance;
import com.example.backend.entity.WeekendAttendanceStatus;
import com.example.backend.repository.UserRepository;
import com.example.backend.repository.WeekendAttendanceRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;

/**
 * Weekend Attendance — employees may only check in/out on Saturdays and
 * Sundays. Mirrors the auth/response conventions already used across the
 * app (see LeaveController / PermissionController): role checks done
 * in-controller against UserRepository, plain-string error bodies for
 * validation failures, employeeName always derived from the authenticated
 * principal rather than trusted from the request body.
 */
@RestController
@RequestMapping("/weekend-attendance")
public class WeekendAttendanceController {

    private boolean bypassWeekendCheck=true;

    private static final List<String> APPROVER_ROLES = List.of("OWNER", "ADMIN", "MANAGER");

    private final WeekendAttendanceRepository repo;
    private final UserRepository userRepo;

    public WeekendAttendanceController(WeekendAttendanceRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private String getUserRole(String username) {
        return userRepo.findByUsername(username)
                .map(u -> u.getRole() != null ? u.getRole() : "USER")
                .orElse("USER");
    }

    private boolean isApprover(String username) {
        return APPROVER_ROLES.contains(getUserRole(username));
    }

    private boolean isOwnRecordOrApprover(String employeeName) {
        String requester = currentUsername();
        return requester.equals(employeeName) || isApprover(requester);
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    // private boolean isWeekend(LocalDate date) {
    //     if (bypassWeekendCheck) {
    //         return true;
    //     }
    //     DayOfWeek dow = date.getDayOfWeek();
    //     return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    // }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ── Request/response DTOs ────────────────────────────────────────────

    public record CheckInRequest(String client, String project) {}

    public record TodayStatusResponse(
            boolean isWeekend,
            String dayOfWeek,
            LocalDate date,
            WeekendAttendance record
    ) {}

    public record WeekendAttendanceSummary(
            String employeeName,
            int weekendDaysAttended,
            double totalHours,
            double averageHoursPerDay
    ) {}

    public record WeekendAttendanceStats(
            String month,
            int totalRecords,
            int totalEmployees,
            int completedRecords,
            int inProgressRecords,
            double totalHours,
            double averageHoursPerRecord
    ) {}

    // ── Employee: today's status ─────────────────────────────────────────
    // Tells the UI whether today is a weekend at all (drives whether the
    // Check In / Check Out controls are enabled) and, if a record already
    // exists for today, its current state.
    @GetMapping("/today")
    public ResponseEntity<?> getToday() {
        String username = currentUsername();
        LocalDate today = LocalDate.now();

        WeekendAttendance record = repo.findByEmployeeNameAndDate(username, today).orElse(null);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(new TodayStatusResponse(isWeekend(today), today.getDayOfWeek().toString(), today, record));
    }

    // ── Employee: check in ───────────────────────────────────────────────
    @PostMapping("/check-in")
    public ResponseEntity<?> checkIn(@RequestBody(required = false) CheckInRequest body) {
        String username = currentUsername();
        LocalDate today = LocalDate.now();

        if (!isWeekend(today)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Attendance can only be submitted on Saturdays and Sundays.");
        }

        Optional<WeekendAttendance> existing = repo.findByEmployeeNameAndDate(username, today);
        if (existing.isPresent()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("You've already checked in today. Only one check-in per weekend day is allowed.");
        }

        WeekendAttendance attendance = new WeekendAttendance();
        attendance.setEmployeeName(username);
        attendance.setDate(today);
        attendance.setDayOfWeek(today.getDayOfWeek().toString());
        attendance.setCheckInTime(LocalDateTime.now());
        attendance.setStatus(WeekendAttendanceStatus.CHECKED_IN);
        if (body != null) {
            attendance.setClient(body.client());
            attendance.setProject(body.project());
        }

        try {
            WeekendAttendance saved = repo.save(attendance);
            return ResponseEntity.ok(saved);
        } catch (DataIntegrityViolationException e) {
            // Race condition guard: two rapid/duplicate requests both passed
            // the check above before either committed. The DB-level unique
            // constraint on (employeeName, date) is the real guarantee.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("You've already checked in today. Only one check-in per weekend day is allowed.");
        }
    }

    // ── Employee: check out ──────────────────────────────────────────────
    @PostMapping("/check-out")
    public ResponseEntity<?> checkOut() {
        String username = currentUsername();
        LocalDate today = LocalDate.now();

        if (!isWeekend(today)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Attendance can only be submitted on Saturdays and Sundays.");
        }

        Optional<WeekendAttendance> existingOpt = repo.findByEmployeeNameAndDate(username, today);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("You need to check in before you can check out.");
        }

        WeekendAttendance attendance = existingOpt.get();
        if (attendance.getStatus() == WeekendAttendanceStatus.CHECKED_OUT || attendance.getCheckOutTime() != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("You've already checked out today.");
        }

        LocalDateTime checkOutTime = LocalDateTime.now();
        attendance.setCheckOutTime(checkOutTime);
        double hours = Duration.between(attendance.getCheckInTime(), checkOutTime).toMinutes() / 60.0;
        attendance.setTotalHours(roundToTwoDecimals(Math.max(hours, 0)));
        attendance.setStatus(WeekendAttendanceStatus.CHECKED_OUT);

        WeekendAttendance saved = repo.save(attendance);
        return ResponseEntity.ok(saved);
    }

    // ── Employee: my weekend attendance history ──────────────────────────
    // employeeName param lets an OWNER/ADMIN/MANAGER pull up a specific
    // employee's history too (used by the admin dashboard's detail view);
    // ordinary employees may only fetch their own.
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam String employeeName,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Integer year
    ) {
        if (!isOwnRecordOrApprover(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }

        List<WeekendAttendance> records;
        if (month != null && !month.isBlank()) {
            YearMonth ym;
            try {
                ym = YearMonth.parse(month); // "yyyy-MM"
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("month must be in yyyy-MM format.");
            }
            records = repo.findByEmployeeNameAndDateBetweenOrderByDateDesc(
                    employeeName, ym.atDay(1), ym.atEndOfMonth());
        } else if (year != null) {
            records = repo.findByEmployeeNameAndDateBetweenOrderByDateDesc(
                    employeeName, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        } else {
            records = repo.findByEmployeeNameOrderByDateDesc(employeeName);
        }

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(records);
    }

    // ── Employee: my weekend hours summary for a year ────────────────────
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(
            @RequestParam String employeeName,
            @RequestParam(required = false) Integer year
    ) {
        if (!isOwnRecordOrApprover(employeeName)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }

        int targetYear = year != null ? year : LocalDate.now().getYear();
        List<WeekendAttendance> records = repo.findByEmployeeNameAndDateBetweenOrderByDateDesc(
                employeeName, LocalDate.of(targetYear, 1, 1), LocalDate.of(targetYear, 12, 31));

        int daysAttended = records.size();
        double totalHours = records.stream()
                .mapToDouble(r -> r.getTotalHours() != null ? r.getTotalHours() : 0.0)
                .sum();
        double avg = daysAttended > 0 ? roundToTwoDecimals(totalHours / daysAttended) : 0.0;

        return ResponseEntity.ok(new WeekendAttendanceSummary(
                employeeName, daysAttended, roundToTwoDecimals(totalHours), avg));
    }

    // ── Owner/Admin: all employees' weekend attendance, filterable ──────
    @GetMapping("/all")
    public ResponseEntity<?> getAll(
            @RequestParam(required = false) String employeeName,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String date,      // yyyy-MM-dd
            @RequestParam(required = false) String month,     // yyyy-MM
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String search     // matches employee name, contains
    ) {
        String role = getUserRole(currentUsername());
        if (!APPROVER_ROLES.contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }

        List<WeekendAttendance> records;
        if (date != null && !date.isBlank()) {
            LocalDate d;
            try {
                d = LocalDate.parse(date);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("date must be in yyyy-MM-dd format.");
            }
            records = repo.findByDateBetweenOrderByDateDesc(d, d);
        } else if (month != null && !month.isBlank()) {
            YearMonth ym;
            try {
                ym = YearMonth.parse(month);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("month must be in yyyy-MM format.");
            }
            records = repo.findByDateBetweenOrderByDateDesc(ym.atDay(1), ym.atEndOfMonth());
        } else if (year != null) {
            records = repo.findByDateBetweenOrderByDateDesc(
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        } else {
            records = repo.findAllByOrderByDateDesc();
        }

        final String empFilter = employeeName != null && !employeeName.isBlank() ? employeeName.trim() : null;
        final String clientFilter = client != null && !client.isBlank() ? client.trim().toLowerCase() : null;
        final String projectFilter = project != null && !project.isBlank() ? project.trim().toLowerCase() : null;
        final String searchFilter = search != null && !search.isBlank() ? search.trim().toLowerCase() : null;

        List<WeekendAttendance> filtered = records.stream()
                .filter(r -> empFilter == null || empFilter.equalsIgnoreCase(r.getEmployeeName()))
                .filter(r -> clientFilter == null
                        || (r.getClient() != null && r.getClient().toLowerCase().contains(clientFilter)))
                .filter(r -> projectFilter == null
                        || (r.getProject() != null && r.getProject().toLowerCase().contains(projectFilter)))
                .filter(r -> searchFilter == null
                        || (r.getEmployeeName() != null && r.getEmployeeName().toLowerCase().contains(searchFilter)))
                .sorted(Comparator.comparing(WeekendAttendance::getDate).reversed()
                        .thenComparing(WeekendAttendance::getEmployeeName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(filtered);
    }

    // ── Owner/Admin: aggregate stats for the dashboard header cards ─────
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(@RequestParam(required = false) String month) {
        String role = getUserRole(currentUsername());
        if (!APPROVER_ROLES.contains(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }

        YearMonth ym;
        if (month != null && !month.isBlank()) {
            try {
                ym = YearMonth.parse(month);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("month must be in yyyy-MM format.");
            }
        } else {
            ym = YearMonth.now();
        }

        List<WeekendAttendance> records = repo.findByDateBetweenOrderByDateDesc(ym.atDay(1), ym.atEndOfMonth());

        int total = records.size();
        long completed = records.stream().filter(r -> r.getStatus() == WeekendAttendanceStatus.CHECKED_OUT).count();
        long inProgress = total - completed;
        long distinctEmployees = records.stream().map(WeekendAttendance::getEmployeeName).distinct().count();
        double totalHours = records.stream()
                .mapToDouble(r -> r.getTotalHours() != null ? r.getTotalHours() : 0.0)
                .sum();
        double avgHours = total > 0 ? roundToTwoDecimals(totalHours / total) : 0.0;

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .body(new WeekendAttendanceStats(
                        ym.toString(), total, (int) distinctEmployees, (int) completed, (int) inProgress,
                        roundToTwoDecimals(totalHours), avgHours));
    }
}