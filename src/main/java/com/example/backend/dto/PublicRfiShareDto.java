package com.example.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * The read-only snapshot returned by the PUBLIC endpoint
 * GET /public/share/rfi/{token}.
 *
 * SECURITY: an explicit whitelist, NOT the Rfi entity. The entity holds internal
 * data that must never reach a client: recipient / CC / sender emails, the email
 * body, attachment file paths, the Gmail thread id, who sent it (usernames) and
 * the free-text response notes. Only add a field here after deciding it is safe
 * for an outside client to see.
 *
 * Every timestamp is an Instant (serialised as ISO-8601 UTC).
 *
 * truncated: true when the project has more RFIs than the page shows (the list
 * is capped so one refresh can never get huge).
 */
public record PublicRfiShareDto(
        String scope,
        String title,
        String client,
        String projectName,
        Instant generatedAt,
        Instant lastChange,
        boolean truncated,
        List<Rfi> rfis) {

    public record Rfi(
            Long id,
            String rfiNumber,
            String subject,
            String discipline,
            String priority,
            String status,
            Instant createdAt,
            Instant responseReceivedAt,
            Instant lastActivityAt,
            List<Event> history) {
    }

    /** One status change, oldest first inside {@link Rfi#history()}. */
    public record Event(String from, String to, Instant at) {
    }
}
