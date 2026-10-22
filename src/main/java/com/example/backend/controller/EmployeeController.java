package com.example.backend.controller;

import com.example.backend.dto.EmployeeResponseDto;
import com.example.backend.dto.EmployeeRoleNameProjection;
import com.example.backend.dto.UpdateEmployeeRequest;
import com.example.backend.entity.EmployeeProfile;
import com.example.backend.entity.User;
import com.example.backend.repository.EmployeeProfileRepository;
import com.example.backend.repository.LeaveRequestRepository;
import com.example.backend.repository.MessageRepository;
import com.example.backend.repository.NotificationRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.repository.WorkReportRepository;
import com.example.backend.service.EmployeeService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/employees")
public class EmployeeController {

    private static final Set<String> ASSIGNABLE_ROLES = Set.of("USER", "LEAD", "MANAGER");
    private static final int ROLE_NAME_MAX_LENGTH = 100;

    private final UserRepository repo;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private com.example.backend.security.CustomUserDetailsService userDetailsCache;

    @Autowired
    private EmployeeProfileRepository employeeProfileRepository;

    // @Autowired
    // private WorkReportRepository workReportRepository;

    //  @Autowired
    // private LeaveRequestRepository leaveRequestRepository;
    
    // @Autowired
    // private MessageRepository messageRepository;
    
    // @Autowired
    // private NotificationRepository notificationRepository;

    public EmployeeController(UserRepository repo) {
        this.repo = repo;
    }

    /** Usernames only — safe for directories/@-mention pickers, any authenticated user. */
    @GetMapping("/name")
    public List<String> getAllByName() {
        return repo.findUsernamesByRoles(List.of("USER", "LEAD"));
    }

        @GetMapping
    // @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public List<EmployeeResponseDto> getAll() {
        List<UserRepository.EmployeeRow> users = repo.findAllEmployeeRows();

        List<String> usernames = users.stream().map(UserRepository.EmployeeRow::getUsername).toList();
        Map<String, String> roleNamesByUsername = employeeProfileRepository
                .findRoleNamesByUsernames(usernames)
                .stream()
                .filter(p -> p.getRoleName() != null && !p.getRoleName().isBlank())
                .collect(Collectors.toMap(EmployeeRoleNameProjection::getUsername, EmployeeRoleNameProjection::getRoleName));

        return users.stream()
                .map(user -> new EmployeeResponseDto(
                        user.getId(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getRole(),
                        roleNamesByUsername.get(user.getUsername())
                ))
                .toList();
    }

    private EmployeeResponseDto toResponseDto(User user) {
        String roleName = employeeProfileRepository.findRoleNameByUsername(user.getUsername()) // no picture load
                .orElse(null);
        return new EmployeeResponseDto(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), roleName);
    }

    /**
     * Updates an employee's system role and/or custom role name (display
     * title). OWNER only. Either field may be omitted/null to leave it
     * unchanged; roleName may be sent as an empty string to clear it.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public EmployeeResponseDto updateEmployee(@PathVariable Long id, @RequestBody UpdateEmployeeRequest request) {
        User user = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        if (request.role() != null && !request.role().isBlank()) {
            String normalizedRole = request.role().trim().toUpperCase();
            if (!ASSIGNABLE_ROLES.contains(normalizedRole)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be one of: USER, LEAD, MANAGER");
            }
            user.setRole(normalizedRole);
            repo.save(user);
            userDetailsCache.evict(user.getUsername()); // role changed -> drop cached login details
        }

        if (request.roleName() != null) {
            String trimmedRoleName = request.roleName().trim();
            if (trimmedRoleName.length() > ROLE_NAME_MAX_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Role name must be " + ROLE_NAME_MAX_LENGTH + " characters or fewer");
            }
            String newRoleName = trimmedRoleName.isEmpty() ? null : trimmedRoleName;
            // The old code loaded the whole profile (incl. picture) even when nothing changed,
            // then re-saved it. Now only the role-name column is written.
            if (employeeProfileRepository.existsByUsername(user.getUsername())) {
                employeeProfileRepository.updateRoleName(user.getUsername(), newRoleName,
                        java.time.LocalDateTime.now());
            } else {
                EmployeeProfile profile = new EmployeeProfile();
                profile.setUsername(user.getUsername());
                profile.setRoleName(newRoleName);
                employeeProfileRepository.save(profile);
            }
        }

        return toResponseDto(user);
    }

    /**
     * Deletes an employee and cascades their data. Previously had NO
     * authorization check — any authenticated user (any role) could delete
     * any other employee, including an OWNER.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
        }
        employeeService.deleteAllLeaveRequestsForUser(id);
    }
}