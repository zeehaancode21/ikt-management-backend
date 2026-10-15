package com.example.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.entity.ApiResponse;
import com.example.backend.service.RfiReplyAutoCheckService;
import com.example.backend.service.RfiReplyAutoCheckService.CheckResult;

/**
 * POST /api/rfi-replies/check
 * Quietly looks for new email replies on the CALLER's own sent RFIs and, if any, imports
 * them (status change + notifications happen exactly as in the manual Sync Gmail).
 * Always scoped to the logged-in user (JWT); no id is taken from the client. Cheap and
 * throttled — see RfiReplyAutoCheckService. Falls under anyRequest().authenticated().
 *
 * Response data: { waiting, imported }. waiting = RFIs being watched (-1 = throttled,
 * nothing was checked); the app uses it to slow down when there is nothing to watch.
 */
@RestController
@RequestMapping("/api/rfi-replies")
public class RfiReplyCheckController {

    private final RfiReplyAutoCheckService service;

    public RfiReplyCheckController(RfiReplyAutoCheckService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public ResponseEntity<ApiResponse<CheckResult>> check() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(ApiResponse.success(service.checkUser(username)));
    }
}