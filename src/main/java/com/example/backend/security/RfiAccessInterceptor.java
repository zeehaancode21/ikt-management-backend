package com.example.backend.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.example.backend.entity.RfiParticipantRole;
import com.example.backend.service.RfiAccessService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The access-control "middleware" for RFI write routes.
 *
 * It runs BEFORE the controller method (and before any file upload is read or
 * any email is sent). If the logged-in user is not on the RFI's To or CC list and did not send the RFI,
 * the request stops right here with 403 Forbidden.
 *
 * Which routes it guards is decided in WebConfig (status / reply / resubmit /
 * reopen). Read routes (view RFI, view history) are never guarded, so
 * view-only people can always look.
 */
@Component
public class RfiAccessInterceptor implements HandlerInterceptor {

    /** The role found here is also stored on the request in case a controller wants it. */
    public static final String ROLE_ATTRIBUTE = "rfi.participantRole";

    @Autowired
    private RfiAccessService rfiAccessService;

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        Map<String, String> pathVars =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String idText = pathVars != null ? pathVars.get("id") : null;
        if (idText == null) {
            return true; // route without an RFI id (e.g. create) - nothing to check here
        }

        Long rfiId;
        try {
            rfiId = Long.valueOf(idText);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid RFI id");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        }

        RfiParticipantRole role = rfiAccessService.requireParticipant(rfiId, auth.getName());
        request.setAttribute(ROLE_ATTRIBUTE, role);
        return true;
    }
}