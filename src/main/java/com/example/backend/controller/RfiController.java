package com.example.backend.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.dto.RfiEmailPreviewDto;
import com.example.backend.dto.RfiSyncResultDto;
import com.example.backend.dto.RfiTimelineEventDto;
import com.example.backend.entity.ApiResponse;
import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiDiscipline;
import com.example.backend.entity.RfiPriority;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiTeamType;
import com.example.backend.service.RfiAccessService;
import com.example.backend.service.RfiGmailSyncService;
import com.example.backend.service.RfiService;
import com.example.backend.service.SubmitDeduplicator;

@RestController
@RequestMapping("/api/project-status/{projectName}/rfis")
@CrossOrigin(origins = {"https://ikt-management-frontend.vercel.app", "http://localhost:5173"})
public class RfiController {

    @Autowired
    private RfiService rfiService;

    @Autowired
    private RfiGmailSyncService rfiGmailSyncService;

    // Who may act on an RFI (To/CC list). The write routes are already blocked by
    // RfiAccessInterceptor (see WebConfig); this is used here to mark each RFI
    // with participantRole + canModify so the app can hide buttons for view-only users.
    @Autowired
    private RfiAccessService rfiAccessService;

    // Collapses double-submits (double click / client retry) into ONE email send.
    @Autowired
    private SubmitDeduplicator submitDeduplicator;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private Rfi withAccess(Rfi rfi) {
        return rfiAccessService.annotate(rfi, currentUsername());
    }

    // ── GET /api/project-status/{projectName}/rfis?teamType=INTERNAL&discipline=STRUCTURAL&priority=HIGH ──
    // priority is taken as a String and parsed by RfiPriority.parse so a bad value
    // returns a clear 400 ("Allowed values: LOW, MEDIUM, HIGH, URGENT"); blank = no filter.
    @GetMapping
    public ResponseEntity<ApiResponse<List<Rfi>>> getRfis(
            @PathVariable String projectName,
            @RequestParam(required = false) RfiTeamType teamType,
            @RequestParam(required = false) RfiDiscipline discipline,
            @RequestParam(required = false) String priority) {
        List<Rfi> rfis = rfiService.getRfisByProject(projectName, teamType, discipline, RfiPriority.parse(priority));
        rfiAccessService.annotate(rfis, currentUsername());
        return ResponseEntity.ok(ApiResponse.success(rfis));
    }

    // ── GET /api/project-status/{projectName}/rfis/{id} ──
    // Constrained to digits only so it can never swallow sibling GET routes
    // like /email-preview below (Spring would otherwise try to parse
    // "email-preview" as this Long id, fail, and 400 — which is what made
    // the email-preview fetch silently come back empty).
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<ApiResponse<Rfi>> getRfiById(
            @PathVariable String projectName,
            @PathVariable Long id) {
        Rfi rfi = withAccess(rfiService.getRfiById(id));
        return ResponseEntity.ok(ApiResponse.success(rfi));
    }

    // ── GET /api/project-status/{projectName}/rfis/{id}/history ──
    /**
     * Gets the chronological communication and status history for an RFI.
     * Timeline includes: RFI Created, Email Sent, Reply Received, Status Changes.
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<RfiTimelineEventDto>>> getRfiHistory(
            @PathVariable String projectName,
            @PathVariable Long id) {
        List<RfiTimelineEventDto> history = rfiService.getRfiHistory(id, currentUsername());
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    // ── GET /api/project-status/{projectName}/rfis/email-preview?subject=... ──
    /**
     * Read-only preview of the generated email subject + default body for a
     * would-be RFI on this project. Persists nothing and does not consume an
     * RFI number. Used by the "New RFI" form to show the default email body
     * (with an Edit option) before the RFI is actually created/sent.
     */
    @GetMapping("/email-preview")
    public ResponseEntity<ApiResponse<RfiEmailPreviewDto>> previewRfiEmail(
            @PathVariable String projectName,
            @RequestParam(value = "subject", required = false) String subject) {
        RfiEmailPreviewDto preview = rfiService.previewEmail(projectName, subject);
        return ResponseEntity.ok(ApiResponse.success(preview));
    }

        @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Rfi>> createRfi(
            @PathVariable String projectName,
            @RequestParam("discipline") RfiDiscipline discipline,
            @RequestParam("teamType") RfiTeamType teamType,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam("subject") String subject,
            @RequestParam(value = "emailBody", required = false) String emailBody,
            @RequestParam("attachment") MultipartFile attachment,
            @RequestParam("senderEmail") String senderEmail,
            @RequestParam(value = "senderName", required = false) String senderName,
            @RequestParam("recipientEmail") String recipientEmail,
            @RequestParam(value = "ccEmails", required = false) String ccEmails) {
        try {
            final String user = currentUsername();
            String dedupeKey = SubmitDeduplicator.key("create", user, projectName, discipline, teamType,
                priority, subject, emailBody, senderEmail, recipientEmail, ccEmails,
                SubmitDeduplicator.fileFingerprint(attachment));
            Rfi created = submitDeduplicator.runOnce(dedupeKey, () -> rfiService.createAndSendRfi(
                projectName, discipline, teamType, RfiPriority.parseOrDefault(priority),
                subject, emailBody, attachment,
                senderEmail, senderName, recipientEmail, ccEmails, user
            ));
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("RFI " + created.getRfiNumber() + " sent successfully", created));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send RFI: " + e.getMessage());
        }
    }

    // ── PUT /api/project-status/{projectName}/rfis/{id}/priority?priority=HIGH ──
    /**
     * Changes an RFI's priority. Same access rule as the other write routes: only people
     * on the RFI's To/CC list (enforced by RfiAccessInterceptor, registered in WebConfig).
     * Returns the updated RFI, including participantRole/canModify for the caller.
     */
    @PutMapping("/{id}/priority")
    public ResponseEntity<ApiResponse<Rfi>> updateRfiPriority(
            @PathVariable String projectName,
            @PathVariable Long id,
            @RequestParam("priority") String priority) {
        Rfi updated = withAccess(rfiService.updatePriority(id, RfiPriority.parse(priority), currentUsername()));
        return ResponseEntity.ok(ApiResponse.success(
            "RFI " + updated.getRfiNumber() + " priority set to " + updated.getPriority(), updated));
    }

    // ── PUT /api/project-status/{projectName}/rfis/{id}/status ──
    /**
     * Manually updates the status of an RFI.
     * The status change is recorded in the history with a timestamp and user info.
     * This is the ONLY way to manually close/reopen an RFI — automatic transitions
     * (like RESPONSE_RECEIVED when a reply arrives) are handled by the Gmail sync service.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Rfi>> updateRfiStatus(
            @PathVariable String projectName,
            @PathVariable Long id,
            @RequestParam("status") RfiStatus newStatus,
            @RequestParam(value = "note", required = false) String note) {
        try {
            Rfi updated = withAccess(rfiService.updateStatus(id, newStatus, note, currentUsername()));
            return ResponseEntity.ok(ApiResponse.success(
                "RFI " + updated.getRfiNumber() + " status updated to " + newStatus, updated));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update RFI status: " + e.getMessage());
        }
    }

    // ── POST /api/project-status/{projectName}/rfis/{id}/resubmit ──
    /**
     * Sends a follow-up message (+ optional attachments) into the RFI's
     * existing Gmail thread and moves its status to RESUBMITTED, atomically.
     */
    @PostMapping(value = "/{id}/resubmit", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Rfi>> resubmitRfi(
            @PathVariable String projectName,
            @PathVariable Long id,
            @RequestParam("message") String message,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) {
        try {
            final String user = currentUsername();
            Rfi updated = submitDeduplicator.runOnce(
                SubmitDeduplicator.key("resubmit", user, id, message, SubmitDeduplicator.filesFingerprint(attachments)),
                () -> withAccess(rfiService.resubmitRfi(id, message, attachments, user)));
            return ResponseEntity.ok(ApiResponse.success(
                "RFI " + updated.getRfiNumber() + " resubmitted", updated));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to resubmit RFI: " + e.getMessage());
        }
    }

    // ── POST /api/project-status/{projectName}/rfis/{id}/reply ──
    /**
     * Replies to an RFI inside its existing Gmail thread. Only people on the RFI's
     * To/CC list can call this (anyone else gets 403 from RfiAccessInterceptor).
     * The global status is set by who replies:
     *   CC replies                  -> RESUBMITTED
     *   TO replies                  -> RESPONSE_RECEIVED
     *   TO replies with resubmit=true -> RESUBMITTED
     */
    @PostMapping(value = "/{id}/reply", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Rfi>> replyToRfi(
            @PathVariable String projectName,
            @PathVariable Long id,
            @RequestParam("message") String message,
            @RequestParam(value = "resubmit", defaultValue = "false") boolean resubmit,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) {
        try {
            final String user = currentUsername();
            Rfi updated = submitDeduplicator.runOnce(
                SubmitDeduplicator.key("reply", user, id, resubmit, message, SubmitDeduplicator.filesFingerprint(attachments)),
                () -> withAccess(rfiService.replyToRfi(id, message, attachments, resubmit, user)));
            return ResponseEntity.ok(ApiResponse.success(
                "RFI " + updated.getRfiNumber() + " reply sent - status is now " + updated.getStatus(), updated));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reply to RFI: " + e.getMessage());
        }
    }

    // ── POST /api/project-status/{projectName}/rfis/{id}/reopen ──
    /**
     * Sends a follow-up message (+ optional attachments) into the RFI's
     * existing Gmail thread and moves its status to REOPENED, atomically.
     * Mirrors resubmitRfi's endpoint shape exactly (same fields, same
     * multipart contract) — this route did not exist before, which is why
     * the "Reopen" action in the UI was failing.
     */
    @PostMapping(value = "/{id}/reopen", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Rfi>> reopenRfi(
            @PathVariable String projectName,
            @PathVariable Long id,
            @RequestParam("message") String message,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments) {
        try {
            final String user = currentUsername();
            Rfi updated = submitDeduplicator.runOnce(
                SubmitDeduplicator.key("reopen", user, id, message, SubmitDeduplicator.filesFingerprint(attachments)),
                () -> withAccess(rfiService.reopenRfi(id, message, attachments, user)));
            return ResponseEntity.ok(ApiResponse.success(
                "RFI " + updated.getRfiNumber() + " reopened", updated));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reopen RFI: " + e.getMessage());
        }
    }

    // ── POST /api/project-status/{projectName}/rfis/{id}/sync ──
    /**
     * Manually triggers a Gmail sync for a single RFI.
     * Fetches all messages in the RFI's Gmail thread and imports new replies.
     * Returns the sync result with count of new messages and any errors.
     */
    @PostMapping("/{id}/sync")
    public ResponseEntity<ApiResponse<RfiSyncResultDto>> syncRfiGmail(
            @PathVariable String projectName,
            @PathVariable Long id) {
        try {
            RfiSyncResultDto result = rfiGmailSyncService.syncRfi(id);
            withAccess(result.getUpdatedRfi());
            String message = result.getSuccess()
                ? "Sync successful. " + result.getNewMessagesCount() + " new message(s) imported."
                : "Sync failed: " + result.getErrorMessage();
            return ResponseEntity.ok(ApiResponse.success(message, result));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to sync RFI: " + e.getMessage());
        }
    }

    // ── POST /api/project-status/{projectName}/rfis/sync-all ──
    /**
     * Syncs all RFIs for a project in one operation.
     * Useful for bulk checking for new replies across multiple RFIs.
     */
    @PostMapping("/sync-all")
    public ResponseEntity<ApiResponse<List<RfiSyncResultDto>>> syncAllRfisForProject(
            @PathVariable String projectName) {
        try {
            List<RfiSyncResultDto> results = rfiGmailSyncService.syncAllForProject(projectName);
            results.forEach(r -> withAccess(r.getUpdatedRfi()));
            int successCount = (int) results.stream().filter(r -> r.getSuccess()).count();
            int failureCount = results.size() - successCount;
            String message = String.format("Sync complete. %d successful, %d failed", successCount, failureCount);
            return ResponseEntity.ok(ApiResponse.success(message, results));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to sync project RFIs: " + e.getMessage());
        }
    }
}