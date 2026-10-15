package com.example.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.Exception.ResourceNotFoundException;
import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiParticipantRole;
import com.example.backend.entity.User;
import com.example.backend.repository.RfiRepository;
import com.example.backend.repository.UserRepository;

/**
 * THE one place that answers: "may this person act on this RFI?"
 *
 * Rule: a user may change status / reply / resubmit ONLY if their email is in the
 * RFI's To list or CC list, OR they are the person who sent the RFI (SENDER).
 * Everybody else is VIEWER = read-only.
 *
 * The To and CC lists are already stored on the RFI itself
 * (Rfi.recipientEmail = To, Rfi.ccEmails = CC), so nothing extra has to be
 * stored or migrated: every existing RFI works immediately.
 *
 * Fails CLOSED: unknown user, user with no email, or a blank email = VIEWER.
 */
@Service
public class RfiAccessService {

    // Finds every email address inside a text, even when it is written like
    // "Doe, John <john@x.com>" (a plain split on commas would break that).
    private static final Pattern EMAIL_FINDER =
            Pattern.compile("[A-Za-z0-9._%+'\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    @Autowired
    private RfiRepository rfiRepository;

    @Autowired
    private UserRepository userRepository;

    // ── Email helpers ────────────────────────────────────────────────────

    /** "Name <A@B.com>" or " A@B.com " -> "a@b.com". Returns null when no email is found. */
    public static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = EMAIL_FINDER.matcher(raw);
        return m.find() ? m.group().toLowerCase() : null;
    }

    /** Every email address found in a comma/semicolon separated list, lower-cased, no duplicates. */
    public static Set<String> parseEmails(String list) {
        Set<String> result = new LinkedHashSet<>();
        if (list == null || list.isBlank()) {
            return result;
        }
        Matcher m = EMAIL_FINDER.matcher(list);
        while (m.find()) {
            result.add(m.group().toLowerCase());
        }
        return result;
    }

    public Set<String> toEmails(Rfi rfi) {
        return parseEmails(rfi.getRecipientEmail());
    }

    public Set<String> ccEmails(Rfi rfi) {
        return parseEmails(rfi.getCcEmails());
    }

    /** To + CC together (used to notify everyone who is "in the loop"). */
    public List<String> participantEmails(Rfi rfi) {
        Set<String> all = new LinkedHashSet<>(toEmails(rfi));
        all.addAll(ccEmails(rfi));
        return new ArrayList<>(all);
    }

    // ── Role lookup ──────────────────────────────────────────────────────

    /**
     * Role of an email address on this RFI. If the address is in BOTH lists,
     * TO wins (the person was addressed directly).
     */
    public RfiParticipantRole roleOfEmail(Rfi rfi, String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return RfiParticipantRole.VIEWER;
        }
        if (toEmails(rfi).contains(normalized)) {
            return RfiParticipantRole.TO;
        }
        if (ccEmails(rfi).contains(normalized)) {
            return RfiParticipantRole.CC;
        }
        return RfiParticipantRole.VIEWER;
    }

    /**
     * Role of a logged-in user (looked up by username -> their account email).
     * TO / CC win; otherwise the user who sent the RFI is SENDER.
     */
    public RfiParticipantRole roleOfUser(Rfi rfi, String username) {
        String email = emailOfUser(username);
        RfiParticipantRole role = roleOfEmail(rfi, email);
        if (role == RfiParticipantRole.VIEWER && isSender(rfi, username, email)) {
            return RfiParticipantRole.SENDER;
        }
        return role;
    }

    /** True when this user created/sent the RFI (by username, or by the sender email on the RFI). */
    private boolean isSender(Rfi rfi, String username, String userEmail) {
        if (username != null && rfi.getSentBy() != null
                && rfi.getSentBy().trim().equalsIgnoreCase(username.trim())) {
            return true;
        }
        String normalizedUserEmail = normalizeEmail(userEmail);
        String senderEmail = normalizeEmail(rfi.getSenderEmail());
        return normalizedUserEmail != null && normalizedUserEmail.equals(senderEmail);
    }

    /** The account email of a username, or null if the user/email doesn't exist. */
    public String emailOfUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsername(username.trim())
                .map(User::getEmail)
                .orElse(null);
    }

    // ── Enforcement ──────────────────────────────────────────────────────

    /** Blocks (403) anyone who is not on the To/CC list and did not send the RFI. Returns the role of an allowed user. */
    public RfiParticipantRole requireParticipant(Rfi rfi, String username) {
        RfiParticipantRole role = roleOfUser(rfi, username);
        if (!role.canModify()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not the sender or on the To or CC list of this RFI, so you can only view it. "
                            + "Changing the status, replying and resubmitting are not allowed.");
        }
        return role;
    }

    /** Same as above but loads the RFI first. */
    public RfiParticipantRole requireParticipant(Long rfiId, String username) {
        Rfi rfi = rfiRepository.findById(rfiId)
                .orElseThrow(() -> new ResourceNotFoundException("RFI not found with ID: " + rfiId));
        return requireParticipant(rfi, username);
    }

    // ── Marking an RFI for the person who is looking at it ───────────────

    /** Fills in participantRole + canModify (not saved to the database). */
    public Rfi annotate(Rfi rfi, String username) {
        if (rfi == null) {
            return null;
        }
        return annotateWithEmail(rfi, username, emailOfUser(username));
    }

    /** Same, for many RFIs, but looks the user up only once. */
    public List<Rfi> annotate(List<Rfi> rfis, String username) {
        if (rfis == null) {
            return null;
        }
        String email = emailOfUser(username);
        for (Rfi rfi : rfis) {
            annotateWithEmail(rfi, username, email);
        }
        return rfis;
    }

    private Rfi annotateWithEmail(Rfi rfi, String username, String email) {
        RfiParticipantRole role = roleOfEmail(rfi, email);
        if (role == RfiParticipantRole.VIEWER && isSender(rfi, username, email)) {
            role = RfiParticipantRole.SENDER;
        }
        rfi.setParticipantRole(role);
        rfi.setCanModify(role.canModify());
        rfi.setReceivedByViewer(isRecipientNotSender(rfi, username, email));
        return rfi;
    }

    /**
     * True when this user RECEIVED the RFI: their email is on the To or CC list
     * and they are not the person who sent it. Used to show "RFI Received"
     * instead of "RFI Sent" to an employee who is sent an INTERNAL RFI.
     * (If someone sends an RFI to themselves they are still the sender.)
     */
    public boolean isReceivedBy(Rfi rfi, String username) {
        return isRecipientNotSender(rfi, username, emailOfUser(username));
    }

    private boolean isRecipientNotSender(Rfi rfi, String username, String email) {
        RfiParticipantRole listRole = roleOfEmail(rfi, email);
        boolean onToOrCc = listRole == RfiParticipantRole.TO || listRole == RfiParticipantRole.CC;
        return onToOrCc && !isSender(rfi, username, email);
    }
}