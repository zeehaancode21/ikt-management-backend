package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for employee endpoints (GET /employees, PUT /employees/{id}).
 *
 * Combines the system `role` (from User — drives permissions, one of
 * USER/LEAD/OWNER/MANAGER) with the custom `roleName` (from EmployeeProfile —
 * a purely cosmetic display title such as "Senior Checker" set by an admin,
 * distinct from the system role). `roleName` is null when the admin hasn't
 * set one for that employee, in which case the UI should fall back to
 * displaying `role`.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeResponseDto {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String roleName;
}