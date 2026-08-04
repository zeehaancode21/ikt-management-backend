package com.example.backend.dto;

/**
 * Spring Data JPA interface-based projection backing
 * {@code ProjectStatusRepository#getProjectsByClientGroupedByYear}.
 *
 * Powers the "Projects" dropdown in the Work Report module: instead of
 * returning a flat, unlabeled list of project names for a client, this
 * carries the {@code year} each project belongs to alongside its name so
 * the frontend can render one dropdown with all of a client's projects
 * organized into per-year sections, rather than filtering the list down
 * to a single selected year.
 */
public interface ProjectYearOption {

    String getYear();

    String getProjectName();
}