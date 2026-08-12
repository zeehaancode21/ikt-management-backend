package com.example.backend.entity;

/**
 * How often a scheduled post repeats. NONE runs exactly once at
 * scheduledFor. WEEKLY / MONTHLY cause a new PENDING ScheduledPost to be
 * automatically queued for the next occurrence right after this one runs
 * (see ScheduledPostService#runOne), so the series keeps going until the
 * user cancels or deletes the upcoming occurrence.
 */
public enum RecurrenceType {
    NONE,
    WEEKLY,
    MONTHLY
}