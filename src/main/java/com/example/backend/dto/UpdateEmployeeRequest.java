package com.example.backend.dto;

/**
 * Request body for PUT /employees/{id}.
 *
 * Both fields are optional/nullable — send only the ones you want to change:
 *   - role: the system role. Must be "USER" or "LEAD" (case-insensitive) if provided.
 *   - roleName: the custom display title, e.g. "Senior Checker". Send an empty
 *     string to clear it (falls back to displaying the system role).
 */
public record UpdateEmployeeRequest(String role, String roleName) {
}