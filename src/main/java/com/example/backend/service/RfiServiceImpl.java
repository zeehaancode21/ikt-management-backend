package com.example.backend.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.Exception.ResourceNotFoundException;
import com.example.backend.dto.RfiEmailPreviewDto;
import com.example.backend.dto.RfiTimelineEventDto;
import com.example.backend.entity.Attachment;
import com.example.backend.entity.ProjectStatus;
import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiDiscipline;
import com.example.backend.entity.RfiPriority;
import com.example.backend.entity.RfiEmailAddressType;
import com.example.backend.entity.RfiEmailDirection;
import com.example.backend.entity.RfiActionSource;
import com.example.backend.entity.RfiEmailMessage;
import com.example.backend.entity.RfiParticipantRole;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiStatusHistory;
import com.example.backend.entity.RfiTeamType;
import com.example.backend.entity.User;
import com.example.backend.repository.AttachmentRepository;
import com.example.backend.repository.ProjectStatusRepository;
import com.example.backend.repository.RfiEmailMessageRepository;
import com.example.backend.repository.RfiRepository;
import com.example.backend.repository.RfiStatusHistoryRepository;
import com.example.backend.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RfiServiceImpl implements RfiService {

    private static final java.util.regex.Pattern EMAIL_PATTERN
            = java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final DateTimeFormatter SUBJECT_DATE_FORMAT
            = DateTimeFormatter.ofPattern("MM-dd-yy");

    private static final String EMAIL_BODY_TEMPLATE =
        "Sir,%n%nPlease find the attached %s.%n%nKindly review and advise.%n%nThank you.";

    @Autowired
    private RfiRepository rfiRepository;

    @Autowired
    private ProjectStatusRepository projectStatusRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private GmailEmailService emailService;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private RfiEmailMessageRepository rfiEmailMessageRepository;

    @Autowired
    private RfiStatusHistoryRepository rfiStatusHistoryRepository;

    // Added so an RFI can be sent/replied-to using the ACTING USER's own
    // connected Gmail account (RfiSettings), instead of the single account
    // GmailEmailService falls back to via GMAIL_REFRESH_TOKEN.
    //
    // Also backs the "RFI Settings" TO/CC address book: TO/CC contacts are
    // now stored as JSON columns on rfi_settings (RfiSettings#emailToContactsJson /
    // #emailCcContactsJson) instead of the old separate rfi_email_contacts
    // table, so recordUsage below goes through this service too.
    @Autowired
    private RfiSettingsService rfiSettingsService;

    @Autowired
    private UserRepository userRepository;

    // Notifies any CC'd employee (in-app + push) on every RFI update, in
    // addition to the actual CC'd email — see its javadoc for the
    // "CC'd = in the loop, not-CC'd = history only" distinction.
    @Autowired
    private RfiCcNotificationService rfiCcNotificationService;

    // Who is allowed to act on an RFI (To/CC list) - see RfiAccessService.
    @Autowired
    private RfiAccessService rfiAccessService;

    // The single place that changes an RFI's status + writes history + notifies.
    @Autowired
    private RfiStatusTransitionService rfiStatusTransitionService;

    /**
     * Refresh token + Gmail address/name + OAuth Client ID/Secret for
     * whichever account an email should actually send from — ALL of it
     * pulled from the acting user's own RFI Settings, so sending/replying
     * never depends on application.properties (GMAIL_CLIENT_ID /
     * GMAIL_CLIENT_SECRET) anymore.
     */
    private static final class SenderCredentials {
        final Long userId;
        final String refreshToken;
        final String fromEmail;
        final String clientId;
        final String clientSecret;
        SenderCredentials(Long userId, String refreshToken, String fromEmail, String clientId, String clientSecret) {
            this.userId = userId;
            this.refreshToken = refreshToken;
            this.fromEmail = fromEmail;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }
    }

    /**
     * Resolves the Gmail account a given user should send/reply as, from
     * their own RfiSettings row (set up on the "RFI Settings" screen) —
     * including that user's own OAuth Client ID/Secret, not the single
     * globally-configured application.properties pair.
     *
     * NOTE: assumes UserRepository has findByUsername(String) and that the
     * `username` passed in (createAndSendRfi's `sentBy` / resubmit/reopen's
     * `performedBy`) is that same username field — check this matches
     * whatever your JWT auth actually stores as the current user's name.
     */
    private SenderCredentials resolveSenderCredentials(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not determine who is sending this RFI");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No account found for: " + username));

        if (!rfiSettingsService.hasValidSettings(user.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Connect your Gmail account (email, Client ID, Client Secret and token) "
                            + "in RFI Settings before sending an RFI");
        }

        String token = rfiSettingsService.getDecryptedToken(user.getId());
        String fromEmail = rfiSettingsService.getGmailEmail(user.getId());
        String clientId = rfiSettingsService.getGmailClientId(user.getId());
        String clientSecret = rfiSettingsService.getDecryptedClientSecret(user.getId());
        return new SenderCredentials(user.getId(), token, fromEmail, clientId, clientSecret);
    }

    /** Comma/semicolon separated -> trimmed, de-duped, blank-filtered list. */
    private static List<String> parseCcList(String ccEmails) {
        List<String> result = new ArrayList<>();
        if (ccEmails == null || ccEmails.isBlank()) {
            return result;
        }
        for (String raw : ccEmails.split("[,;]")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Retrieves all RFIs for a specific project filtered by team type and
     * discipline.
     */
    @Override
    public List<Rfi> getRfisByProject(String projectName, RfiTeamType teamType, RfiDiscipline discipline, RfiPriority priority) {
        if (projectName == null || projectName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }

        // Verify project exists
        projectStatusRepository.findByProjectName(projectName)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectName));

        // Query RFIs by project name, team type, and discipline
        return rfiRepository.findByProjectNameWithFilters(projectName, teamType, discipline, priority);
    }

    /**
     * Changes an RFI's priority. Deliberately does NOT go through RfiStatusHistory:
     * that table records status transitions (new_status is NOT NULL), and priority is
     * plain metadata, so it must not send email, touch the thread, or move the status.
     */
    @Override
    @Transactional
    public Rfi updatePriority(Long rfiId, RfiPriority priority, String changedBy) {
        if (priority == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priority is required");
        }
        Rfi rfi = getRfiById(rfiId);
        RfiPriority previous = rfi.getPriority();
        if (previous == priority) {
            return rfi;
        }
        rfi.setPriority(priority);
        Rfi saved = rfiRepository.save(rfi);
        log.info("RFI {} priority changed {} -> {} by {}", saved.getRfiNumber(), previous, priority, changedBy);
        return saved;
    }

    /**
     * Retrieves a specific RFI by its ID.
     */
    @Override
    public Rfi getRfiById(Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid RFI ID is required");
        }

        return rfiRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RFI not found with ID: " + id));
    }

    /**
     * Read-only preview of the generated email subject + default body for a
     * would-be RFI on this project — same format createAndSendRfi falls back
     * to, but computed without persisting anything or consuming an RFI
     * number. Backs the editable email-body preview on the "New RFI" form.
     */
    @Override
    public RfiEmailPreviewDto previewEmail(String projectName, String subject) {
        if (projectName == null || projectName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }
        projectStatusRepository.findByProjectName(projectName)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectName));

        String previewSubject = (subject == null || subject.isBlank()) ? "Subject" : subject.trim();
        String generatedEmailSubject = buildGeneratedEmailSubject(projectName, previewSubject);
        String emailBody = defaultEmailBody(generatedEmailSubject);
        return new RfiEmailPreviewDto(generatedEmailSubject, emailBody);
    }

    private String buildGeneratedEmailSubject(String projectName, String subject) {
        String rfiNumber = nextRfiNumber(projectName);
        String currentDate = LocalDate.now().format(SUBJECT_DATE_FORMAT);
        return String.format("%s-%s-%s_%s", projectName, rfiNumber, subject, currentDate);
    }

    private String defaultEmailBody(String generatedEmailSubject) {
        return String.format(EMAIL_BODY_TEMPLATE, generatedEmailSubject);
    }

    /**
     * Creates, stores, and emails an RFI in one step. There is no "draft" state
     * — if the email fails to send, nothing is persisted (the just-stored
     * attachment is deleted too).
     *
     * recipientEmail is user-typed (not read from the project) and, once the
     * send succeeds, is written back onto ProjectStatus.rfiRecipientEmail so
     * the next RFI on this project pre-fills with it.
     *
     * Also captures the Gmail threadId of the sent message onto the RFI, and
     * writes the initial two history rows (RfiEmailMessage OUTGOING +
     * RfiStatusHistory "created") so the communication/status timeline
     * starts complete from the moment the RFI is issued.
     */
    @Override
    @Transactional
    public Rfi createAndSendRfi(
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
    ) throws Exception {

        // ── Validation ──────────────────────────────────────────────────
        ProjectStatus project = projectStatusRepository.findByProjectName(projectName)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectName));

        if (discipline == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discipline is required");
        }
        if (teamType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Team type is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subject is required");
        }
        if (attachment == null || attachment.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A PDF attachment is required");
        }
        if (senderEmail == null || !EMAIL_PATTERN.matcher(senderEmail).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid sender email is required");
        }
        if (recipientEmail == null || !EMAIL_PATTERN.matcher(recipientEmail).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid recipient email is required");
        }

        // ── Parse + validate CC list ─────────────────────────────────────
        // Comma/semicolon separated, blank entries ignored. Any non-blank
        // entry that isn't a valid address fails loudly (rather than being
        // silently dropped) so a typo in a CC address doesn't quietly send
        // without it.
        List<String> ccList = new ArrayList<>();
        if (ccEmails != null && !ccEmails.isBlank()) {
            for (String raw : ccEmails.split("[,;]")) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"" + trimmed + "\" is not a valid CC email address");
                }
                if (!ccList.contains(trimmed)) {
                    ccList.add(trimmed);
                }
            }
        }
        String normalizedCcEmails = ccList.isEmpty() ? null : String.join(", ", ccList);

        String originalName = attachment.getOriginalFilename();
        boolean looksLikePdf
                = MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(attachment.getContentType())
                && originalName != null
                && originalName.toLowerCase().endsWith(".pdf");
        if (!looksLikePdf) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are accepted");
        }

        // ── Generate number + email subject/body ───────────────────────
        String rfiNumber = nextRfiNumber(projectName);
        String currentDate = LocalDate.now().format(SUBJECT_DATE_FORMAT);
        String generatedEmailSubject = String.format("%s-%s-%s_%s", projectName, rfiNumber, subject, currentDate);
        String resolvedEmailBody = (emailBody == null || emailBody.isBlank())
                ? defaultEmailBody(generatedEmailSubject)
                : emailBody;
        log.info("RFI email body for {}: {} (length {})",
                generatedEmailSubject,
                (emailBody == null || emailBody.isBlank()) ? "using generated default" : "using caller-supplied override",
                resolvedEmailBody.length());

        // ── Store the file (reuses the existing attachment pipeline) ───
        Attachment savedAttachment;
        try {
            savedAttachment = fileStorageService.storeFiles(List.of(attachment), sentBy).get(0);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store attachment: " + e.getMessage());
        }

        // ── Resolve which Gmail account this actually sends from ────────
        // sentBy is the acting user's username — this pulls THEIR own
        // connected Gmail token/address from RfiSettings, rather than the
        // one global account GmailEmailService otherwise falls back to.
        SenderCredentials creds = resolveSenderCredentials(sentBy);

        // ── Send the email; on failure, undo the stored file ────────────
        GmailSendResult sendResult;
        try {
            log.info("sendWithAttachment ccList=[{}]", ccList);
            sendResult = emailService.sendWithAttachment(
                    recipientEmail,
                    generatedEmailSubject,
                    resolvedEmailBody,
                    attachment.getBytes(),
                    originalName,
                    attachment.getContentType(),
                    senderEmail,
                    ccList,
                    creds.refreshToken,
                    creds.fromEmail,
                    senderName,
                    creds.clientId,
                    creds.clientSecret
            );
        } catch (Exception e) {
            attachmentRepository.delete(savedAttachment);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to send RFI email: " + e.getMessage());
        }

        // ── Persist only after the email has actually gone out ─────────
        Rfi rfi = new Rfi();
        rfi.setProjectName(projectName);
        rfi.setRfiNumber(rfiNumber);
        rfi.setDiscipline(discipline);
        rfi.setTeamType(teamType);
        // Null (client sent nothing) -> MEDIUM. Invalid values were already rejected as 400 upstream.
        rfi.setPriority(priority != null ? priority : RfiPriority.DEFAULT);
        rfi.setSubject(subject);
        rfi.setGeneratedEmailSubject(generatedEmailSubject);
        rfi.setEmailBody(resolvedEmailBody);
        rfi.setRecipientEmail(recipientEmail);
        rfi.setCcEmails(normalizedCcEmails);
        rfi.setSenderEmail(senderEmail);
        rfi.setSenderName(senderName);
        rfi.setAttachment(savedAttachment);
        rfi.setStatus(RfiStatus.SENT);
        rfi.setSentBy(sentBy);
        rfi.setCreatedAt(LocalDateTime.now());
        rfi.setGmailThreadId(sendResult.getThreadId());
        Rfi saved = rfiRepository.saveAndFlush(rfi);
        RfiEmailMessage outgoing = new RfiEmailMessage();
        outgoing.setRfi(saved);
        outgoing.setDirection(RfiEmailDirection.OUTGOING);
        outgoing.setGmailMessageId(sendResult.getMessageId());
        outgoing.setGmailThreadId(sendResult.getThreadId());
        outgoing.setFromAddress(senderEmail);
        outgoing.setToAddress(recipientEmail);
        outgoing.setCcAddress(normalizedCcEmails);
        outgoing.setSubject(generatedEmailSubject);
        outgoing.setBodyText(resolvedEmailBody);
        outgoing.setSnippet(resolvedEmailBody.length() > 200 ? resolvedEmailBody.substring(0, 200) : resolvedEmailBody);
        outgoing.setMessageDate(saved.getCreatedAt());
        outgoing.getAttachments().add(savedAttachment);
        rfiEmailMessageRepository.save(outgoing);

        // ── Seed the status history ─────────────────────────────────────
        RfiStatusHistory created = new RfiStatusHistory();
        created.setRfi(saved);
        created.setOldStatus(null);
        created.setNewStatus(RfiStatus.SENT);
        created.setChangedBy(sentBy);
        created.setNote("RFI created and issued to " + recipientEmail);
        rfiStatusHistoryRepository.save(created);

        // ── Save the typed recipient + CC list back onto the project for reuse ───
        // Same idea as rfiRecipientEmail: the NEXT RFI created on this project
        // defaults its To/CC fields to whatever was used last time, instead of
        // starting empty. normalizedCcEmails is null when no CC was used —
        // that's fine, it just makes the next RFI's CC default empty too.
        project.setRfiRecipientEmail(recipientEmail);
        project.setRfiCcEmails(normalizedCcEmails);
        projectStatusRepository.save(project);

                // ── Post-commit extras (address book + notifications) ──────────
        // Run only AFTER the RFI is safely committed, each in its own
        // transaction, so a failure here can never roll back or fail a send
        // that has already gone out.
        final Long senderUserId = creds.userId;
        final String fromLabel = (senderName != null ? senderName : sentBy);

        final List<String> toList = new ArrayList<>(RfiAccessService.parseEmails(recipientEmail));
        final Set<String> toLower = new HashSet<>(toList);
        final List<String> ccOnlyList = new ArrayList<>();
        for (String cc : ccList) {
            String normalized = RfiAccessService.normalizeEmail(cc);
            if (normalized == null || !toLower.contains(normalized)) {
                ccOnlyList.add(cc);
            }
        }

        final Runnable postSendExtras = () -> {
            try {
                rfiSettingsService.recordContactUsage(senderUserId, RfiEmailAddressType.TO, recipientEmail);
                for (String cc : ccList) {
                    rfiSettingsService.recordContactUsage(senderUserId, RfiEmailAddressType.CC, cc);
                }
            } catch (Exception e) {
                log.warn("Failed to record TO/CC address(es) for RFI {}: {}", rfiNumber, e.getMessage());
            }
            try {
                rfiCcNotificationService.notifyCcEmployees(
                        toList,
                        sentBy,
                        "New RFI " + rfiNumber,
                        "You received RFI " + rfiNumber + " (" + projectName + "): \"" + subject
                                + "\", sent by " + fromLabel + "."
                );
                rfiCcNotificationService.notifyCcEmployees(
                        ccOnlyList,
                        sentBy,
                        "New RFI " + rfiNumber,
                        "You were CC'd on RFI " + rfiNumber + " (" + projectName + "): \"" + subject
                                + "\", sent to " + recipientEmail + " by " + fromLabel + "."
                );
            } catch (Exception e) {
                log.warn("Failed to send notifications for RFI {}: {}", rfiNumber, e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    postSendExtras.run();
                }
            });
        } else {
            postSendExtras.run();
        }

        return saved;
    }

    /**
     * User-controlled status transition. Always logged to RfiStatusHistory.
     * This is the ONLY way an RFI is manually closed/reopened/etc — the
     * Gmail sync job (RfiGmailSyncService) only ever moves a non-terminal
     * RFI to RESPONSE_RECEIVED, and never touches CLOSED/CANCELLED RFIs.
     */
    @Override
    @Transactional
    public Rfi updateStatus(Long rfiId, RfiStatus newStatus, String note, String changedBy) {
        if (newStatus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A target status is required");
        }

        Rfi rfi = getRfiById(rfiId);

        // Only people on the To/CC list may change the status (403 for everyone else).
        RfiParticipantRole role = rfiAccessService.requireParticipant(rfi, changedBy);

        // Status + history + notifications all happen in the one shared place.
        return rfiStatusTransitionService.transition(
                rfi, newStatus, changedBy, role, RfiActionSource.APP, note, false);
    }

    /**
     * Builds the chronological (oldest-first) timeline the frontend renders:
     * RFI Created -> Email Sent -> Response Received -> ... -> Status Changed.
     */
    @Override
    public List<RfiTimelineEventDto> getRfiHistory(Long rfiId) {
        return getRfiHistory(rfiId, null);
    }

    @Override
    public List<RfiTimelineEventDto> getRfiHistory(Long rfiId, String viewerUsername) {
        Rfi rfi = getRfiById(rfiId);

        // Did the person looking at this RFI RECEIVE it (To/CC, not the sender)?
        // If so, the first email in the thread (the RFI itself) is "RFI Received"
        // for them, not "Email Sent".
        boolean viewerReceivedIt = viewerUsername != null
                && rfiAccessService.isReceivedBy(rfi, viewerUsername);
        boolean firstOutgoingMarked = false;

        List<RfiTimelineEventDto> events = new ArrayList<>();

        RfiTimelineEventDto createdEvent = new RfiTimelineEventDto();
        createdEvent.setType("RFI_CREATED");
        createdEvent.setTimestamp(rfi.getCreatedAt());
        createdEvent.setSender(rfi.getSenderEmail());
        createdEvent.setRecipient(rfi.getRecipientEmail());
        createdEvent.setCc(rfi.getCcEmails());
        createdEvent.setSubject(rfi.getGeneratedEmailSubject());
        createdEvent.setMessage("RFI " + rfi.getRfiNumber() + " created by " + rfi.getSentBy());
        createdEvent.setPerformedBy(rfi.getSentBy());
        events.add(createdEvent);

        for (RfiEmailMessage email : rfiEmailMessageRepository.findByRfiIdOrderByMessageDateAsc(rfiId)) {
            RfiTimelineEventDto event = new RfiTimelineEventDto();
            event.setType(email.getDirection() == RfiEmailDirection.OUTGOING ? "EMAIL_SENT" : "REPLY_RECEIVED");
            if (viewerReceivedIt && !firstOutgoingMarked && email.getDirection() == RfiEmailDirection.OUTGOING) {
                event.setReceivedByViewer(true);
                firstOutgoingMarked = true;
            }
            event.setTimestamp(email.getMessageDate());
            event.setSender(email.getFromAddress());
            event.setRecipient(email.getToAddress());
            event.setCc(email.getCcAddress());
            event.setSubject(email.getSubject());
            event.setMessage(email.getBodyText() != null ? email.getBodyText() : email.getSnippet());
            event.setAttachments(email.getAttachments());
            event.setGmailMessageId(email.getGmailMessageId());
            event.setGmailThreadId(email.getGmailThreadId());
            events.add(event);
        }

        for (RfiStatusHistory history : rfiStatusHistoryRepository.findByRfiIdOrderByChangedAtAsc(rfiId)) {
            RfiTimelineEventDto event = new RfiTimelineEventDto();
            event.setType("STATUS_CHANGED");
            event.setTimestamp(history.getChangedAt());
            event.setFromStatus(history.getOldStatus());
            event.setToStatus(history.getNewStatus());
            event.setPerformedBy(history.getChangedBy());
            event.setMessage(history.getNote());
            events.add(event);
        }

        events.sort(Comparator.comparing(RfiTimelineEventDto::getTimestamp));
        return events;
    }

    @Override
    @Transactional
    public Rfi resubmitRfi(Long rfiId, String message, List<MultipartFile> attachments, String performedBy) throws Exception {
        return sendFollowUpAndTransition(
            rfiId, message, attachments, performedBy,
            RfiStatus.RESUBMITTED,
            "A message is required to resubmit",
            "Resubmitted in the same thread",
            "Resubmitted in the same thread with %d attachment(s)"
        );
    }

    /**
     * Reply to an RFI. The status is decided by WHO replies (see RfiStatusPolicy):
     *   CC  -> RESUBMITTED
     *   TO  -> RESPONSE_RECEIVED, or RESUBMITTED when explicitResubmit is true
     * Anyone not on the To/CC list gets 403 and nothing is sent.
     */
    @Override
    @Transactional
    public Rfi replyToRfi(Long rfiId, String message, List<MultipartFile> attachments,
                          boolean explicitResubmit, String performedBy) throws Exception {
        Rfi rfi = getRfiById(rfiId);
        RfiParticipantRole role = rfiAccessService.requireParticipant(rfi, performedBy);
        RfiStatus targetStatus = RfiStatusPolicy.statusForReply(role, explicitResubmit);

        boolean resubmitted = targetStatus == RfiStatus.RESUBMITTED;
        return sendFollowUpAndTransition(
            rfiId, message, attachments, performedBy,
            targetStatus,
            "A message is required to reply",
            resubmitted ? "Resubmitted in the same thread (" + role + " participant)"
                        : "Reply sent in the same thread (" + role + " participant)",
            (resubmitted ? "Resubmitted" : "Reply sent") + " in the same thread with %d attachment(s) (" + role + " participant)"
        );
    }

    @Override
    @Transactional
    public Rfi reopenRfi(Long rfiId, String message, List<MultipartFile> attachments, String performedBy) throws Exception {
        return sendFollowUpAndTransition(
            rfiId, message, attachments, performedBy,
            RfiStatus.REOPENED,
            "A message is required to reopen",
            "Reopened in the same thread",
            "Reopened in the same thread with %d attachment(s)"
        );
    }

    /**
     * Sends a follow-up message (resubmit/reopen) into the SAME EXISTING Gmail thread.
     *
     * Same-thread rules (all three must hold or Gmail starts a new thread):
     *  1. threadId       = rfi.getGmailThreadId()  (original thread)
     *  2. In-Reply-To /
     *     References     = the REAL RFC 822 Message-ID of the last message in that thread
     *                      (GmailEmailService reads it from Gmail — the DB only stores
     *                      Gmail's internal id, which is not a valid Message-ID)
     *  3. Subject        = the thread's own subject (GmailEmailService reads it too)
     *  4. Mailbox        = the account that owns the thread, i.e. rfi.getSentBy() —
     *                      NOT whoever happens to click Resubmit.
     */
    private Rfi sendFollowUpAndTransition(
            Long rfiId,
            String message,
            List<MultipartFile> attachments,
            String performedBy,
            RfiStatus targetStatus,
            String blankMessageError,
            String noteNoAttachments,
            String noteWithAttachmentsFormat
    ) throws Exception {
        // Access check FIRST - before any file is stored or email is sent.
        // Only people on the To/CC list may reply / resubmit / reopen.
        Rfi rfi = getRfiById(rfiId);
        RfiParticipantRole actorRole = rfiAccessService.requireParticipant(rfi, performedBy);

        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, blankMessageError);
        }

        // ── Ensure RFI has a Gmail thread ID (must exist for follow-ups) ──
        if (rfi.getGmailThreadId() == null || rfi.getGmailThreadId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot resubmit/reopen: RFI has no Gmail thread ID. Original send may have failed.");
        }

        List<MultipartFile> nonEmptyAttachments = new ArrayList<>();
        if (attachments != null) {
            for (MultipartFile f : attachments) {
                if (f != null && !f.isEmpty()) {
                    nonEmptyAttachments.add(f);
                }
            }
        }

        List<Attachment> savedAttachments = new ArrayList<>();
        if (!nonEmptyAttachments.isEmpty()) {
            try {
                savedAttachments = fileStorageService.storeFiles(nonEmptyAttachments, performedBy);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store attachment(s): " + e.getMessage());
            }
        }

        // Fallback only — GmailEmailService replaces this with the thread's real subject.
        String replySubject = "Re: " + rfi.getGeneratedEmailSubject();

        // Gmail internal id of the newest message we know about in this thread
        // (either our own last send or the recipient's latest reply). It's only a HINT:
        // GmailEmailService looks up its real Message-ID header, and if this is null
        // or not found it simply uses the last message of the thread.
        String lastGmailMessageId = rfiEmailMessageRepository
                .findFirstByRfiIdOrderByMessageDateDesc(rfiId)
                .map(RfiEmailMessage::getGmailMessageId)
                .orElse(null);

        log.info("Follow-up on RFI {}: threadId={}, lastGmailMessageId={}, performedBy={}, sentBy={}",
                rfi.getRfiNumber(), rfi.getGmailThreadId(), lastGmailMessageId, performedBy, rfi.getSentBy());

        // Carry forward the same CC list the RFI was originally sent with —
        // without this, resubmit/reopen only emailed the "to" address and
        // silently dropped every CC recipient.
        List<String> ccList = new ArrayList<>();
        if (rfi.getCcEmails() != null && !rfi.getCcEmails().isBlank()) {
            for (String raw : rfi.getCcEmails().split("[,;]")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    ccList.add(trimmed);
                }
            }
        }

        // The Gmail thread lives in the mailbox of the user who ORIGINALLY sent the RFI
        // (rfi.getSentBy() — the same account RfiGmailSyncService reads it with).
        // Replying from any other account can't see that thread and would start a new one,
        // so always use the thread owner's credentials here.
        String threadOwner = (rfi.getSentBy() != null && !rfi.getSentBy().isBlank())
                ? rfi.getSentBy() : performedBy;
        SenderCredentials creds;
        try {
            creds = resolveSenderCredentials(threadOwner);
        } catch (ResponseStatusException e) {
            for (Attachment a : savedAttachments) {
                attachmentRepository.delete(a);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This RFI's email thread belongs to " + threadOwner + "'s Gmail account, so the "
                            + "follow-up must be sent from it. " + e.getReason());
        }

        GmailSendResult sendResult;
        try {
            List<byte[]> bytesList = new ArrayList<>();
            List<String> filenames = new ArrayList<>();
            List<String> contentTypes = new ArrayList<>();
            for (MultipartFile f : nonEmptyAttachments) {
                bytesList.add(f.getBytes());
                filenames.add(f.getOriginalFilename());
                contentTypes.add(f.getContentType());
            }

            // GmailEmailService turns threadId + lastGmailMessageId into the real
            // In-Reply-To / References / Subject so the reply lands in the same thread.
            sendResult = emailService.sendReplyInThread(
                    rfi.getRecipientEmail(),
                    replySubject,
                    message,
                    bytesList,
                    filenames,
                    contentTypes,
                    rfi.getSenderEmail(),
                    ccList,
                    rfi.getGmailThreadId(),       // ← USE EXISTING THREAD ID
                    lastGmailMessageId,            // ← IN-REPLY-TO FOR SAME THREAD
                    creds.refreshToken,
                    creds.fromEmail,
                    rfi.getSenderName(),
                    creds.clientId,
                    creds.clientSecret
            );
        } catch (Exception e) {
            for (Attachment a : savedAttachments) {
                attachmentRepository.delete(a);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to send follow-up email: " + e.getMessage());
        }

        // ── Verify thread ID wasn't lost (should still be the SAME) ──
        String resultThreadId = sendResult.getThreadId();
        if (!rfi.getGmailThreadId().equals(resultThreadId)) {
            // The email is already sent, so don't throw (a retry would send a duplicate).
            log.error("Thread ID mismatch after follow-up on RFI {}! Expected: {}, Got: {}. "
                    + "Gmail created a new thread instead of replying.",
                    rfi.getRfiNumber(), rfi.getGmailThreadId(), resultThreadId);
        }

        // Do NOT overwrite the thread ID — it should remain the ORIGINAL one
        // (only set if somehow it was null initially, which shouldn't happen)
        if (rfi.getGmailThreadId() == null || rfi.getGmailThreadId().isBlank()) {
            rfi.setGmailThreadId(resultThreadId);
        }
        
        // Status + history + notifications in the one shared place. The history
        // row is written even if the status is already the target, so every
        // reply/resubmit shows on the timeline.
        String historyNote = savedAttachments.isEmpty()
                ? noteNoAttachments
                : String.format(noteWithAttachmentsFormat, savedAttachments.size());
        Rfi saved = rfiStatusTransitionService.transition(
                rfi, targetStatus, performedBy, actorRole, RfiActionSource.APP, historyNote, true);

        RfiEmailMessage outgoing = new RfiEmailMessage();
        outgoing.setRfi(saved);
        outgoing.setDirection(RfiEmailDirection.OUTGOING);
        outgoing.setGmailMessageId(sendResult.getMessageId());
        outgoing.setGmailThreadId(resultThreadId);  // Should match rfi.getGmailThreadId()
        outgoing.setFromAddress(rfi.getSenderEmail());
        outgoing.setToAddress(rfi.getRecipientEmail());
        outgoing.setCcAddress(rfi.getCcEmails());
        outgoing.setSubject(replySubject);
        outgoing.setBodyText(message);
        outgoing.setSnippet(message.length() > 200 ? message.substring(0, 200) : message);
        outgoing.setMessageDate(LocalDateTime.now());
        outgoing.getAttachments().addAll(savedAttachments);
        rfiEmailMessageRepository.save(outgoing);

        return saved;
    }

    private String nextRfiNumber(String projectName) {
        long count = rfiRepository.countByProjectName(projectName);
        return String.format("RFI#%03d", count + 1);
    }
}