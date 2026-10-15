package com.example.backend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.backend.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Module gate for RFI: "may this person use the RFI module at all?"
 *
 * - OWNER: always yes (no database lookup).
 * - Everyone else: yes only if the owner switched them on in Admin Console -> RFI Access
 *   (users.rfi_access = true). The flag is read from the database on every call, so
 *   granting or revoking takes effect immediately - no re-login needed.
 *
 * Which routes it guards is decided in WebConfig. Fails CLOSED: unknown user,
 * NULL flag or any doubt = 403.
 */
@Component
public class RfiModuleAccessInterceptor implements HandlerInterceptor {

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true; // CORS pre-flight carries no login
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        }

        if (isOwner(auth) || hasRfiAccess(auth.getName())) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You do not have access to the RFI module. Ask the owner to grant it.");
    }

    /** Shared with the /api/rfi-access/me endpoint so both always agree. */
    public boolean canUse(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && (isOwner(auth) || hasRfiAccess(auth.getName()));
    }

    private boolean isOwner(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }

    private boolean hasRfiAccess(String username) {
        return username != null && userRepository.findRfiAccessByUsername(username).orElse(false);
    }
}