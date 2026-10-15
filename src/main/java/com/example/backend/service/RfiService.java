package com.example.backend.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.example.backend.dto.RfiEmailPreviewDto;
import com.example.backend.dto.RfiTimelineEventDto;
import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiDiscipline;
import com.example.backend.entity.RfiPriority;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiTeamType;

public interface RfiService {

    /** Null teamType / discipline / priority means "don't filter on that field". */
    List<Rfi> getRfisByProject(String projectName, RfiTeamType teamType, RfiDiscipline discipline, RfiPriority priority);

    Rfi getRfiById(Long id);

    /**
     * Read-only preview of the generated email subject + default email body
     * for a would-be RFI on this project, WITHOUT persisting anything or
     * consuming an RFI number. Powers the editable email-body preview on the
     * "New RFI" form.
     */
    RfiEmailPreviewDto previewEmail(String projectName, String subject);

        /**
     * @param emailBody optional user-edited email body. When null/blank, the
     *                   default template (identical to previewEmail's) is
     *                   used — same behavior as before this field existed.
     *                   When provided, it's sent as-is instead of the
     *                   generated default.
     * @param ccEmails   optional comma-separated list of CC recipient
     *                   addresses. Blank entries are ignored. Each address
     *                   that's actually used is recorded onto the RFI
     *                   Settings "known CC addresses" list once the send
     *                   succeeds — see RfiSettingsService#recordContactUsage.
     * @param priority   optional. Null falls back to {@link RfiPriority#DEFAULT} (MEDIUM).
     */
    Rfi createAndSendRfi(
        String projectName,
        RfiDiscipline discipline,
        RfiTeamType teamType,
        RfiPriority priority,
        String subject,
        String emailBody,
        MultipartFile attachment,
        String senderEmail,
        String senderName,
        String recipientEmail,
        String ccEmails,
        String sentBy
    ) throws Exception;

    /**
     * User-controlled status change (Close / Reopen / Outstanding / Under
     * Review / etc). Always recorded in RfiStatusHistory with changedBy and
     * a timestamp. Never invoked automatically by the Gmail sync job — see
     * RfiGmailSyncService, which is the only other place status is changed.
     */
    Rfi updateStatus(Long rfiId, RfiStatus newStatus, String note, String changedBy);

    /**
     * Changes an RFI's priority. Priority is metadata only: it never touches the
     * status, the Gmail thread, or anything already sent. No-op when unchanged.
     */
    Rfi updatePriority(Long rfiId, RfiPriority priority, String changedBy);

    /**
     * The RFI's full communication + status timeline, oldest first:
     * RFI Created -> Email Sent -> Response Received -> ... -> Status Changed.
     */
    List<RfiTimelineEventDto> getRfiHistory(Long rfiId);

    /**
     * Same timeline, but marks (per viewer) the original RFI email as
     * "received" when the viewer is a recipient and not the sender.
     */
    List<RfiTimelineEventDto> getRfiHistory(Long rfiId, String viewerUsername);

    /**
     * Sends a follow-up message (+ optional attachments) into the RFI's
     * EXISTING Gmail thread and moves its status to RESUBMITTED in one
     * atomic operation. Recorded as an EMAIL_SENT message and a
     * STATUS_CHANGED history row, same as createAndSendRfi / updateStatus.
     */
    Rfi resubmitRfi(Long rfiId, String message, List<MultipartFile> attachments, String performedBy) throws Exception;

    /**
     * Sends a follow-up message (+ optional attachments) into the RFI's
     * EXISTING Gmail thread and moves its status to REOPENED in one atomic
     * operation. Identical contract to resubmitRfi, just a different target
     * status — used from the "Reopen" action once an RFI is CLOSED.
     */
    Rfi reopenRfi(Long rfiId, String message, List<MultipartFile> attachments, String performedBy) throws Exception;

    /**
     * Sends a reply into the RFI's existing Gmail thread. Only people on the
     * To/CC list may do this. The new global status depends on who replies:
     *   CC replies                -> RESUBMITTED
     *   TO replies                -> RESPONSE_RECEIVED
     *   TO replies + resubmit=true -> RESUBMITTED
     * See RfiStatusPolicy.
     */
    Rfi replyToRfi(Long rfiId, String message, List<MultipartFile> attachments,
                   boolean explicitResubmit, String performedBy) throws Exception;
}