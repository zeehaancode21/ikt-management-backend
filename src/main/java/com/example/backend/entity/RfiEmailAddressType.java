package com.example.backend.entity;

/**
 * Distinguishes which address slot on the "New RFI" form an
 * TO/CC entry stored in {@link RfiSettings} belongs to — the "Send To" address vs a
 * "CC" address. Kept as its own enum (rather than a boolean) so the
 * frontend/API stays readable and any future address slot (e.g. BCC)
 * can be added without a schema change.
 */
public enum RfiEmailAddressType {
    TO,
    CC
}