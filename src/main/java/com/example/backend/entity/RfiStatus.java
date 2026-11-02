package com.example.backend.entity;

/**
 * Complete RFI lifecycle statuses supporting the steel detailing workflow.
 *
 * Every new RFI (internal or external) starts as OPEN. SENT is the OLD name of that
 * state; it is kept only so records created before OPEN existed still load.
 * Treat SENT exactly like OPEN everywhere.
 *
 * Users can transition to OUTSTANDING, UNDER_REVIEW, REOPEN, CANCEL at any point.
 *
 * Internal-RFI funnel (Drafter -> Checker -> Owner -> Client) adds two statuses:
 *   INTERNAL_APPROVED - the Checker approved the INTERNAL RFI ("Approved (Internal)").
 *                       Terminal for the internal record; it is linked to the generated draft.
 *   DRAFTING          - the auto-generated EXTERNAL draft waiting for the Owner to edit and
 *                       dispatch. Nothing has been emailed to the client yet.
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
    SENT,
    OPEN,
    INTERNAL_APPROVED,
    DRAFTING
}