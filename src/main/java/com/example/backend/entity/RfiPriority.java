package com.example.backend.entity;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * How urgently an RFI needs an answer. Stored as its name (EnumType.STRING),
 * so never reorder or rename values without a data migration.
 * Declaration order is low -> high, so ordinal() is safe to use for sorting in code.
 */
public enum RfiPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT;

    /** Used whenever the client sends no priority. */
    public static final RfiPriority DEFAULT = MEDIUM;

    private static final String ALLOWED =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));

    /**
     * Lenient parse for request values: trims, ignores case.
     * Returns null for null/blank (meaning "not provided").
     * Anything else that isn't a known priority is a 400 with a readable message,
     * instead of Spring's generic type-mismatch error.
     */
    public static RfiPriority parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            // Cap what we echo back so a huge/hostile value can't bloat the response.
            String shown = cleaned.length() > 20 ? cleaned.substring(0, 20) + "…" : cleaned;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid priority '" + shown + "'. Allowed values: " + ALLOWED);
        }
    }

    /** Like {@link #parse}, but a missing value becomes {@link #DEFAULT} (used on create). */
    public static RfiPriority parseOrDefault(String raw) {
        RfiPriority parsed = parse(raw);
        return parsed != null ? parsed : DEFAULT;
    }
}