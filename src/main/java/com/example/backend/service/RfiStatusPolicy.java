package com.example.backend.service;

import java.util.regex.Pattern;

import com.example.backend.entity.RfiParticipantRole;
import com.example.backend.entity.RfiStatus;

/**
 * The status rules, and NOTHING else (no database, no Spring) so they are
 * easy to read and easy to test.
 *
 * Names used in the requirement  ->  names in RfiStatus.java
 *   "Resubmit"        ->  RESUBMITTED
 *   "Reply Received"  ->  RESPONSE_RECEIVED
 *
 * When someone REPLIES (in the app, or by email):
 *
 *   who replied | plain reply                       | explicit "resubmit"
 *   ------------+----------------------------------+--------------------
 *   CC          | RESUBMITTED  (CC default)        | RESUBMITTED
 *   TO          | RESPONSE_RECEIVED                | RESUBMITTED
 *   SENDER      | RESUBMITTED  (originator)        | RESUBMITTED
 *   VIEWER      | not allowed                      | not allowed
 */
public final class RfiStatusPolicy {

    private RfiStatusPolicy() {
    }

    // "[Resubmit]" or "[Resubmitted]" written in the email subject
    private static final Pattern SUBJECT_TAG =
            Pattern.compile("\\[\\s*resubmit(ted)?\\s*\\]", Pattern.CASE_INSENSITIVE);

    // the word resubmit / resubmitted / resubmitting in the FIRST line of the reply
    private static final Pattern FIRST_LINE_WORD =
            Pattern.compile("\\bresubmit(ted|ting)?\\b", Pattern.CASE_INSENSITIVE);

    /** The global status that a reply from this kind of participant must produce. */
    public static RfiStatus statusForReply(RfiParticipantRole role, boolean explicitResubmit) {
        if (role == null || !role.canModify()) {
            throw new IllegalArgumentException("Only the sender or To/CC participants can reply to an RFI");
        }
        if (role == RfiParticipantRole.CC || role == RfiParticipantRole.SENDER) {
            return RfiStatus.RESUBMITTED;
        }
        return explicitResubmit ? RfiStatus.RESUBMITTED : RfiStatus.RESPONSE_RECEIVED;
    }

    /**
     * Did the sender of an inbound email mark it as a resubmission?
     * Yes if the subject contains [Resubmit], or the first non-empty line of the
     * body contains the word "resubmit". Only the first line is checked, so a
     * quoted older message further down cannot trigger it by accident.
     */
    public static boolean isExplicitResubmit(String subject, String bodyText) {
        if (subject != null && SUBJECT_TAG.matcher(subject).find()) {
            return true;
        }
        if (bodyText == null) {
            return false;
        }
        for (String line : bodyText.split("\\R")) {
            if (!line.isBlank()) {
                return FIRST_LINE_WORD.matcher(line).find();
            }
        }
        return false;
    }
}