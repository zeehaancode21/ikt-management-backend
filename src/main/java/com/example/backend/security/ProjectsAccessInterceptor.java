package com.example.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.backend.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Module gate for Projects (the Dashboard page): "may this person use the Projects module?"
 *
 * - OWNER: always yes (no database lookup).
 * - Everyone else: yes only if the owner ticked "Projects" for them in
 *   Admin Console -> Access Control (users.projects_access = true). The flag is read from the
 *   database on every call, so granting or revoking takes effect immediately - no re-login.
 *
 * Fails CLOSED: unknown user, NULL flag or any doubt = 403.
 *
 * WebConfig creates two instances (this is NOT a @Component, so there is no extra bean):
 *   safeMethodsAllowed = true  -> /project-status/**  : GET stays open because Work Report,
 *                                 Work Hours and the RFI form read the project list; only
 *                                 create / edit / delete are gated.
 *   safeMethodsAllowed = false -> change-order routes : every call is gated.
 */
public class ProjectsAccessInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;
    private final boolean safeMethodsAllowed;

    public ProjectsAccessInterceptor(UserRepository userRepository, boolean safeMethodsAllowed) {
        this.userRepository = userRepository;
        this.safeMethodsAllowed = safeMethodsAllowed;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if ("OPTIONS".equals(method)) {
            return true; // CORS pre-flight carries no login
        }
        if (safeMethodsAllowed && ("GET".equals(method) || "HEAD".equals(method))) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        }

        if (isOwner(auth) || hasProjectsAccess(auth.getName())) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You do not have access to Projects. Ask the owner to grant it.");
    }

    private boolean isOwner(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
    }

    private boolean hasProjectsAccess(String username) {
        return username != null && userRepository.findModuleAccessByUsername(username)
                .map(row -> Boolean.TRUE.equals(row.getProjects()))
                .orElse(false);
    }
}