package com.example.backend.dto;

/**
 * Spring Data JPA interface-based projection backing
 * {@code ProjectStatusRepository#getAllClientsGroupedByYear}.
 *
 * Mirrors {@link ProjectYearOption}: instead of returning a flat,
 * unlabeled list of client names, this carries the {@code year} each
 * client had activity in alongside the client name, so the frontend can
 * render a single "Client" list organized into per-year sections (as
 * used by the Work Report and Owner's Hours Dashboard "Projects"
 * dropdown for projects) rather than a flat alphabetical list.
 */
public interface ClientYearOption {

    String getYear();

    String getClientName();
}