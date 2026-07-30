package com.example.backend.controller;

import com.example.backend.dto.WorkHoursSummaryDto;
import com.example.backend.entity.WorkReport;
import com.example.backend.repository.UserRepository;
import com.example.backend.repository.WorkReportRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reports")
public class WorkReportController {

    private final WorkReportRepository repo;
    private final UserRepository userRepo;

    public WorkReportController(WorkReportRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();
    }

    /** True if the given username has the OWNER role — owners can manage everyone's reports. */
    private boolean isOwner(String username) {
        return userRepo.findByUsername(username)
                .map(u -> "OWNER".equalsIgnoreCase(u.getRole()))
                .orElse(false);
    }

    // ─────────────────────────────────────────────────────────
    //  EMPLOYEE endpoints
    // ─────────────────────────────────────────────────────────

    /** Employee: submit one or more rows for a single date */
    @PostMapping("/submit")
    public ResponseEntity<List<WorkReport>> submit(
            @RequestBody List<WorkReport> reports) {

        String username = currentUsername();
        reports.forEach(r -> r.setEmployeeName(username));
        return ResponseEntity.ok(repo.saveAll(reports));
    }

    /**
     * Employee: update existing reports for a specific date.
     * Replaces all reports for the given date with the new payload.
     */
    @PutMapping("/update/{date}")
    public ResponseEntity<List<WorkReport>> update(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody List<WorkReport> reports) {

        String username = currentUsername();
        
        // Delete existing reports for this user on this date
        List<WorkReport> existingReports = repo.findByEmployeeNameAndDateOrderByIdAsc(username, date);
        if (!existingReports.isEmpty()) {
            repo.deleteAll(existingReports);
        }
        
        // Save new reports
        reports.forEach(r -> {
            r.setId(null); // Ensure new IDs are generated
            r.setEmployeeName(username);
            r.setDate(date);
        });
        
        return ResponseEntity.ok(repo.saveAll(reports));
    }

    /** Employee: fetch all own reports, newest date first */
    @GetMapping("/my")
    public ResponseEntity<List<WorkReport>> getMy() {
        return ResponseEntity.ok(
                repo.findByEmployeeNameOrderByDateDesc(currentUsername()));
    }

    /**
     * Employee: fetch own reports for a single date.
     * GET /reports/my/by-date?date=2024-06-15
     */
    @GetMapping("/my/by-date")
    public ResponseEntity<List<WorkReport>> getMyByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(
                repo.findByEmployeeNameAndDateOrderByIdAsc(currentUsername(), date));
    }

    // ─────────────────────────────────────────────────────────
    //  OWNER endpoints
    // ─────────────────────────────────────────────────────────

    /**
     * Owner: fetch every report across all employees.
     * Optionally filter by date and/or employeeName via query params.
     *
     * Examples:
     *   GET /reports/all                              → all records
     *   GET /reports/all?date=2024-06-15              → all employees on that date
     *   GET /reports/all?employeeName=john            → all dates for john
     *   GET /reports/all?date=2024-06-15&employeeName=john
     */
    @GetMapping("/all")
    public ResponseEntity<List<WorkReport>> getAll(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,

            @RequestParam(required = false) String employeeName) {

        // If neither filter is supplied, use the simple unfiltered query for efficiency
        if (date == null && (employeeName == null || employeeName.isBlank())) {
            return ResponseEntity.ok(repo.findAllByOrderByDateDesc());
        }

        String empFilter = (employeeName != null && employeeName.isBlank()) ? null : employeeName;
        return ResponseEntity.ok(repo.findAllFiltered(date, empFilter));
    }

    /**
     * Owner/Lead: Hours Dashboard — aggregated totals grouped into
     * Modeling / Checking / (E Plan + Shop Drawing + Linking + Part Drawing),
     * plus the grand total, filterable by client and/or project.
     *
     * Both filters are optional — omit either (or both) to get org-wide totals.
     *
     * Examples:
     *   GET /reports/summary
     *   GET /reports/summary?client=Acme%20Corp
     *   GET /reports/summary?client=Acme%20Corp&project=Warehouse%20Expansion
     */
    @GetMapping("/summary")
    public ResponseEntity<WorkHoursSummaryDto> getHoursSummary(
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String project) {

        String clientFilter  = (client  == null || client.isBlank())  ? null : client;
        String projectFilter = (project == null || project.isBlank()) ? null : project;

        List<Object[]> rows = repo.sumHoursByWorkType(clientFilter, projectFilter);

        Map<WorkReport.WorkType, Double> raw = new EnumMap<>(WorkReport.WorkType.class);
        for (Object[] row : rows) {
            WorkReport.WorkType type = (WorkReport.WorkType) row[0];
            Double sum = (Double) row[1];
            raw.put(type, sum == null ? 0.0 : sum);
        }

        double modeling    = raw.getOrDefault(WorkReport.WorkType.MODELING, 0.0);
        double checking    = raw.getOrDefault(WorkReport.WorkType.CHECKING, 0.0);
        double ePlan       = raw.getOrDefault(WorkReport.WorkType.E_PLAN, 0.0);
        double shopDrawing = raw.getOrDefault(WorkReport.WorkType.SHOP_DRAWING, 0.0);
        double linking     = raw.getOrDefault(WorkReport.WorkType.LINKING, 0.0);
        double partDrawing = raw.getOrDefault(WorkReport.WorkType.PART_DRAWING, 0.0);

        double drawingGroup = ePlan + shopDrawing + linking + partDrawing;
        double total        = modeling + checking + drawingGroup;

        // Individual component/class breakdown, exposed alongside the combined totals.
        Map<String, Double> hoursByType = new LinkedHashMap<>();
        hoursByType.put("MODELING", modeling);
        hoursByType.put("CHECKING", checking);
        hoursByType.put("E_PLAN", ePlan);
        hoursByType.put("SHOP_DRAWING", shopDrawing);
        hoursByType.put("LINKING", linking);
        hoursByType.put("PART_DRAWING", partDrawing);

        WorkHoursSummaryDto dto = new WorkHoursSummaryDto(
                clientFilter,
                projectFilter,
                hoursByType,
                modeling,
                checking,
                drawingGroup,
                total
        );

        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
public ResponseEntity<Void> deleteReport(@PathVariable Long id) {
    String username = currentUsername();

    WorkReport report = repo.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    boolean callerIsOwner = isOwner(username);

    // Owners can delete anyone's work report, any time — the ownership
    // check and the 10-minute window only apply to non-owners deleting
    // their own entries.
    if (!callerIsOwner) {
        if (!report.getEmployeeName().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your record");
        }

        if (report.getCreatedAt().plusMinutes(10).isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Delete window has expired");
        }
    }

    repo.delete(report);
    return ResponseEntity.noContent().build();
}
}