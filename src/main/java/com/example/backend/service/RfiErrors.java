package com.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * One place that turns a raw exception into a message that is safe to show to a user.
 *
 * Before, handlers returned things like  "Failed to send RFI: " + e.getMessage()  which leaks
 * Google API / SQL / class details to the browser. Now the real error goes to the server log
 * and the user gets a short, plain message.
 */
public final class RfiErrors {

    private static final Logger log = LoggerFactory.getLogger(RfiErrors.class);

    public static final String GMAIL_EXPIRED =
            "Gmail connection expired. Reconnect in RFI Settings.";

    private RfiErrors() {
    }

    public static boolean isInvalidGrant(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.toLowerCase().contains("invalid_grant")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The text a user should see for an unexpected failure (never the raw exception text). */
    public static String friendlyMessage(String what, Throwable e) {
        if (isInvalidGrant(e)) {
            return GMAIL_EXPIRED;
        }
        return what + ". Please try again. If it keeps failing, check your Gmail connection in RFI Settings.";
    }

    /** Logs the real error, returns the exception to throw (500). */
    public static ResponseStatusException internal(String what, Throwable e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, what, e);
    }

    /** Same, with a chosen status (e.g. 502 when Gmail itself failed). */
    public static ResponseStatusException build(HttpStatus status, String what, Throwable e) {
        log.error("{} -> {}: {}", what, e.getClass().getSimpleName(), e.getMessage(), e);
        return new ResponseStatusException(status, friendlyMessage(what, e));
    }
}