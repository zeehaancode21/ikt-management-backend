package com.example.backend.entity;

/**
 * Where an RFI is in the Internal workflow (Drafter -> Checker -> Owner -> Client).
 * NULL on plain external RFIs and on RFIs created before this workflow existed.
 *
 *  CHECKER_REVIEW     internal RFI, waiting for the Checker (status OPEN)
 *  REVISION_REQUESTED internal RFI, Checker sent it back to the Drafter (UNDER_REVIEW / OUTSTANDING)
 *  APPROVED_INTERNAL  internal RFI, Checker approved it (status INTERNAL_APPROVED)
 *  DRAFT_OWNER        the generated EXTERNAL draft, waiting for the Owner (status DRAFTING)
 *  DISPATCHED         the draft was sent to the client; it is now a live external RFI
 */
public enum RfiInternalStage {
    CHECKER_REVIEW,
    REVISION_REQUESTED,
    APPROVED_INTERNAL,
    DRAFT_OWNER,
    DISPATCHED
}