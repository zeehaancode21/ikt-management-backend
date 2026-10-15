package com.example.backend.entity;

/**
 * Where a status change came from. Stored in the status history so the
 * timeline can tell an in-app action apart from an inbound email.
 *
 *  APP   - the web app or the mobile app (both call the same API)
 *  EMAIL - an inbound email picked up by the Gmail sync
 */
public enum RfiActionSource {
    APP,
    EMAIL
}