package com.example.backend.entity;

/**
 * Complete RFI lifecycle statuses supporting the steel detailing workflow.
 * 
 * DRAFT → SUBMITTED → ISSUED → AWAITING_RESPONSE → RESPONSE_RECEIVED → CLOSED
 * But users can transition to OUTSTANDING, UNDER_REVIEW, REOPEN, CANCEL at any point.
 */
public enum RfiStatus {
    DRAFT,
    SUBMITTED,
    ISSUED,
    AWAITING_RESPONSE,
    RESPONSE_RECEIVED,
    UNDER_REVIEW,
    OUTSTANDING,
    CLOSED,
    REOPENED,
    CANCELLED,
    RESUBMITTED,
    SENT
}