package com.example.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * The read-only snapshot returned by the PUBLIC endpoint GET /public/share/{token}.
 *
 * SECURITY: this is deliberately a separate, explicit whitelist and NOT the
 * ProjectStatus / ChangeOrder entities. The entities contain internal fields
 * (e.g. ProjectStatus.rfiRecipientEmail) that must never reach a client.
 * Only add a field here after deciding it is safe for an external client to see.
 *
 * {@code team} and every {@code remarks} value are null unless the link was
 * created with showDetails = true ({@code detailsIncluded} tells the UI which).
 */
public record PublicShareDto(
        String scope,
        String title,
        String client,
        String year,
        boolean detailsIncluded,
        Instant generatedAt,
        Instant lastChange,
        List<Project> projects) {

    public record Project(
            Long id,
            String client,
            String projectName,
            String jobNumber,
            String year,
            String projectManager,
            String approvalStatus,
            String fabStatus,
            String ifaDate,
            String ifcDate,
            String team,
            String remarks,
            Instant updatedAt,
            List<ChangeOrder> changeOrders) {
    }

    public record ChangeOrder(
            Long id,
            String co,
            String description,
            String status,
            Double amount,
            String ifaDate,
            String ifaPer,
            String iffDate,
            String iffPer,
            String remarks,
            Instant updatedAt) {
    }
}