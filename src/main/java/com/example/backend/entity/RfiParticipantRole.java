package com.example.backend.entity;

/**
 * Where a user stands on ONE specific RFI.
 *
 *  TO     - the user's email is in the RFI's "To" list   -> can act
 *  CC     - the user's email is in the RFI's "CC" list   -> can act
 *  SENDER - the user sent this RFI (not on To/CC)        -> can act
 *  VIEWER - none of the above                            -> read-only
 */
public enum RfiParticipantRole {
    TO,
    CC,
    SENDER,
    VIEWER;

    /** True for TO, CC and SENDER: the roles allowed to change, reply to or resubmit an RFI. */
    public boolean canModify() {
        return this == TO || this == CC || this == SENDER;
    }
}