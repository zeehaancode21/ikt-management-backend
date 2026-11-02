package com.example.backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    // A number followed by a dot at the very start of a project name ("20. Tower A").
    // "20 Tower A" (no dot) is NOT matched and stays as is.
    private static final java.util.regex.Pattern LEADING_NUMBER_DOT
            = java.util.regex.Pattern.compile("^\\d+\\s*\\.\\s*");

    private static final String EMAIL_BODY_TEMPLATE =
        "Sir,%n%nPlease find the attached %s.%n%nKindly review and advise.%n%nThank you.";

    @Autowired
    private RfiRepository rfiRepository;

    @Autowired
    private ProjectStatusRepository projectStatusRepository;

    @Autowired
    private FileStorageService fileStorageService;

    // Makes sent files live in the Gmail thread instead of being copied into the database.
    @Autowired
    private GmailAttachmentService gmailAttachmentService;

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

    // Keeps the public client share pages live: told whenever an RFI is created or its
    // priority changes (status changes are announced from RfiStatusTransitionService).
    @Autowired
    private PublicRfiShareService publicRfiShareService;

    // ── Limits: a file is held in memory about 3-4x its size while it is sent (bytes + MIME buffer
    // + base64 text), which matters on a small instance. Override in application.properties.
    @Value("${app.rfi.max-attachment-bytes:10485760}")
    private long maxAttachmentBytes = 10L * 1024 * 1024;

    @Value("${app.rfi.max-followup-total-bytes:20971520}")
    private long maxFollowUpTotalBytes = 20L * 1024 * 1024;

    // The email that is SENT is never cut; only the copy kept in the database is capped.
    @Value("${app.rfi.stored-body-max-chars:20000}")
    private int storedBodyMaxChars = EmailBodyCleaner.DEFAULT_MAX_CHARS;

    // ── Short database transactions only. Gmail calls run with NO transaction (and no pooled
    // connection) open - see createAndSendRfi / sendFollowUpAndTransition.
    @Autowired
    private PlatformTransactionManager transactionManager;

    private volatile TransactionTemplate txTemplate;

    private TransactionTemplate tx() {
        TransactionTemplate t = txTemplate;
        if (t == null) {
            t = new TransactionTemplate(transactionManager);
            txTemplate = t;
        }
        return t;
    }

    // ── One create at a time per project: "read highest number, add one" is not atomic, and the
    // number is already inside the email subject before the row is saved. Striped locks keep
    // memory fixed (64 locks). This covers a single backend instance; the unique index
    // (project_name, rfi_number) is the safety net if you ever run more than one.
    private static final int LOCK_STRIPES = 64;
    private static final long PROJECT_LOCK_WAIT_SECONDS = 90;
    private final ReentrantLock[] projectLocks = createLocks();

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private ReentrantLock lockFor(String projectName) {
        int h = projectName == null ? 0 : projectName.trim().toLowerCase().hashCode();
        return projectLocks[Math.floorMod(h, LOCK_STRIPES)];
    }

    /**
     * Runs the database part that follows an email that has ALREADY gone out. Retries once; if it
     * still fails, logs everything needed to restore the record by hand and tells the user NOT to
     * send again (a retry from the UI would email the recipient a second time).
     */
    private <T> T persistAfterSend(String what, GmailSendResult sent, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("Saving {} after the email was sent failed (attempt {}): {}", what, attempt, e.toString());
                if (attempt == 1) {
                    try {
                        java.lang.Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        java.lang.Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("EMAIL SENT BUT NOT SAVED: {} gmailMessageId={} gmailThreadId={} - restore this record manually, do NOT resend.",
                what, sent.getMessageId(), sent.getThreadId(), last);
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "The email for " + what + " was sent, but saving it failed. Do NOT send it again - "
                        + "contact the admin (Gmail message " + sent.getMessageId() + ").");
    }

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
     * Retrieves the RFIs of EVERY project (newest first), filtered by team type,
     * discipline and priority. Backs the "All projects" view of the RFI dashboard.
     * One query, attachments joined in (no N+1).
     */
    @Override
    @Transactional(readOnly = true)
    public List<Rfi> getAllRfis(RfiTeamType teamType, RfiDiscipline discipline, RfiPriority priority) {
        return rfiRepository.findAllWithFilters(teamType, discipline, priority);
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
        publicRfiShareService.evictProjectAfterCommit(saved.getProjectName());
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
    public RfiEmailPreviewDto previewEmail(String projectName, String subject, Integer startingNumber) {
        if (projectName == null || projectName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }
        projectStatusRepository.findByProjectName(projectName)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectName));

        validateStartingNumber(startingNumber);
        String previewSubject = (subject == null || subject.isBlank()) ? "Subject" : subject.trim();
        String rfiNumber = nextRfiNumber(projectName, startingNumber);
        String generatedEmailSubject = buildGeneratedEmailSubject(projectName, rfiNumber, previewSubject);
        String emailBody = defaultEmailBody(projectName, rfiNumber, previewSubject);
        boolean hasExisting = rfiRepository.countByProjectName(projectName) > 0;
        return new RfiEmailPreviewDto(generatedEmailSubject, emailBody, rfiNumber, hasExisting);
    }

    private String buildGeneratedEmailSubject(String projectName, String rfiNumber, String subject) {
        String currentDate = LocalDate.now().format(SUBJECT_DATE_FORMAT);
        return String.format("%s-%s-%s_%s", cleanProjectName(projectName), rfiNumber, subject, currentDate);
    }

    /**
     * Project name as used in the email subject AND body: a leading "number + dot"
     * is dropped ("20. Project name" -> "Project name"). A number without a dot
     * ("20 Project name") is kept. If nothing would be left, the name is kept as is.
     */
    private String cleanProjectName(String projectName) {
        if (projectName == null) {
            return "";
        }
        String trimmed = projectName.trim();
        String cleaned = LEADING_NUMBER_DOT.matcher(trimmed).replaceFirst("").trim();
        return cleaned.isEmpty() ? trimmed : cleaned;
    }

    private String defaultEmailBody(String projectName, String rfiNumber, String subject) {
        return String.format(EMAIL_BODY_TEMPLATE, buildGeneratedEmailSubject(projectName, rfiNumber, subject));
    }

    /**
     * Creates, stores, and emails an RFI in one step. There is no "draft" state
     * - if the email fails to send, nothing is persisted (the just-stored
     * attachment is deleted too).
     *
     * NO method-level @Transactional (on purpose). The order is:
     *   1. validate (no transaction)
     *   2. lock this project, pick the next RFI number
     *   3. send the email through Gmail            <- no database connection is held
     *   4. save RFI + first email + first history row + project defaults in ONE short transaction
     *      (retried once; if it still fails the log has everything to restore it, and the user is
     *      told NOT to send again)
     *   5. address book + notifications (each in its own transaction, can never undo the send)
     *
     * recipientEmail is user-typed (not read from the project) and, once the
     * send succeeds, is written back onto ProjectStatus.rfiRecipientEmail so
     * the next RFI on this project pre-fills with it.
     */
    @Override
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
            String sentBy,
            Integer startingNumber
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

        // ── From address must never be a recipient ───────────────────────
        // "From" = the sender's own email AND the Gmail account the mail goes out from.
        // Resolved here (before anything is stored) so a missing Gmail setup fails early.
        SenderCredentials creds = resolveSenderCredentials(sentBy);
        Set<String> fromAddresses = new HashSet<>();
        fromAddresses.add(senderEmail.trim().toLowerCase());
        if (creds.fromEmail != null && !creds.fromEmail.isBlank()) {
            fromAddresses.add(creds.fromEmail.trim().toLowerCase());
        }
        String recipientLc = recipientEmail.trim().toLowerCase();
        if (fromAddresses.contains(recipientLc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your own (From) address can't be used as the To address");
        }

        // ── Parse + validate CC list ─────────────────────────────────────
        // Comma/semicolon separated, blank entries ignored. Any non-blank
        // entry that isn't a valid address fails loudly (rather than being
        // silently dropped) so a typo in a CC address doesn't quietly send
        // without it. The From address and the To address are never kept in
        // CC (an address is either To or CC, not both).
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
                String trimmedLc = trimmed.toLowerCase();
                if (fromAddresses.contains(trimmedLc) || trimmedLc.equals(recipientLc)) {
                    continue;
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
        if (attachment.getSize() > maxAttachmentBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The PDF is too large (maximum " + (maxAttachmentBytes / (1024 * 1024))
                            + " MB). Please compress it and try again.");
        }

        validateStartingNumber(startingNumber);

        // ── One create at a time per project (number + send + save) ───────
        final ReentrantLock projectLock = lockFor(projectName);
        boolean locked;
        try {
            locked = projectLock.tryLock(PROJECT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            java.lang.Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The request was interrupted. Please try again.");
        }
        if (!locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another RFI for this project is being sent right now. Please try again in a moment.");
        }

        final Rfi saved;
        final String rfiNumber;
        try {
            // ── Generate number + email subject/body ───────────────────────
            rfiNumber = nextRfiNumber(projectName, startingNumber);
            final String generatedEmailSubject = buildGeneratedEmailSubject(projectName, rfiNumber, subject);
            final String resolvedEmailBody = (emailBody == null || emailBody.isBlank())
                    ? defaultEmailBody(projectName, rfiNumber, subject)
                    : emailBody;
            log.info("RFI email body for {}: {} (length {})",
                    generatedEmailSubject,
                    (emailBody == null || emailBody.isBlank()) ? "using generated default" : "using caller-supplied override",
                    resolvedEmailBody.length());

            // ── Record the file (WITHOUT copying its bytes into the database) ───
            final Attachment savedAttachment = fileStorageService.storePlaceholder(attachment, sentBy);

            // ── Send the email (NO transaction open); on failure, undo the stored file ──
            final GmailSendResult sendResult;
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
                throw RfiErrors.build(HttpStatus.BAD_GATEWAY, "Failed to send the RFI email", e);
            }

            // ── The email is out: point the attachment at its copy in the Gmail thread ──
            // (never throws; falls back to keeping the bytes in the database if Gmail can't be read)
            gmailAttachmentService.linkSentAttachments(
                    List.of(savedAttachment), List.of(attachment), sendResult.getMessageId(),
                    sentBy, creds.refreshToken, creds.clientId, creds.clientSecret);

            // ── Persist only after the email has actually gone out, in ONE short transaction ──
            final String storedBody = EmailBodyCleaner.cap(resolvedEmailBody, storedBodyMaxChars);
            final String sentByTrimmed = sentBy == null ? null : sentBy.trim();   // stored trimmed -> index-friendly
            final String ccToStore = normalizedCcEmails;
            saved = this.<Rfi>persistAfterSend("RFI " + rfiNumber, sendResult, () -> tx().<Rfi>execute(status -> {
                Rfi rfi = new Rfi();
                rfi.setProjectName(projectName);
                rfi.setRfiNumber(rfiNumber);
                rfi.setDiscipline(discipline);
                rfi.setTeamType(teamType);
                // Null (client sent nothing) -> MEDIUM. Invalid values were already rejected as 400 upstream.
                rfi.setPriority(priority != null ? priority : RfiPriority.DEFAULT);
                rfi.setSubject(subject);
                rfi.setGeneratedEmailSubject(generatedEmailSubject);
                rfi.setEmailBody(storedBody);
                rfi.setRecipientEmail(recipientEmail);
                rfi.setCcEmails(ccToStore);
                rfi.setSenderEmail(senderEmail);
                rfi.setSenderName(senderName);
                rfi.setAttachment(savedAttachment);
                rfi.setStatus(RfiStatus.SENT);
                rfi.setSentBy(sentByTrimmed);
                rfi.setCreatedAt(LocalDateTime.now());
                rfi.setGmailThreadId(sendResult.getThreadId());
                Rfi savedRfi = rfiRepository.saveAndFlush(rfi);

                RfiEmailMessage outgoing = new RfiEmailMessage();
                outgoing.setRfi(savedRfi);
                outgoing.setDirection(RfiEmailDirection.OUTGOING);
                outgoing.setGmailMessageId(sendResult.getMessageId());
                outgoing.setGmailThreadId(sendResult.getThreadId());
                outgoing.setFromAddress(senderEmail);
                outgoing.setToAddress(recipientEmail);
                outgoing.setCcAddress(ccToStore);
                outgoing.setSubject(generatedEmailSubject);
                outgoing.setBodyText(storedBody);
                outgoing.setSnippet(storedBody.length() > 200 ? storedBody.substring(0, 200) : storedBody);
                outgoing.setMessageDate(LocalDateTime.now());
                outgoing.getAttachments().add(savedAttachment);
                rfiEmailMessageRepository.save(outgoing);

                // ── Seed the status history ─────────────────────────────────
                RfiStatusHistory created = new RfiStatusHistory();
                created.setRfi(savedRfi);
                created.setOldStatus(null);
                created.setNewStatus(RfiStatus.SENT);
                created.setChangedBy(sentByTrimmed);
                created.setNote("RFI created and issued to " + recipientEmail);
                rfiStatusHistoryRepository.save(created);

                // ── Save the typed recipient + CC list back onto the project for reuse ──
                ProjectStatus projectRow = projectStatusRepository.findByProjectName(projectName)
                        .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectName));
                projectRow.setRfiRecipientEmail(recipientEmail);
                projectRow.setRfiCcEmails(ccToStore);
                projectStatusRepository.save(projectRow);

                // A new RFI should show up on any client page that lists this project's RFIs.
                publicRfiShareService.evictProjectAfterCommit(projectName);
                return savedRfi;
            }));
        } finally {
            projectLock.unlock();
        }

        // ── Post-send extras (address book + notifications) ──────────────
        // Run only AFTER the RFI is safely committed, each in its own transaction, so a failure
        // here can never roll back or fail a send that has already gone out.
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

        // Memory guard: the files are held in RAM while the email is built and sent.
        long totalBytes = 0;
        for (MultipartFile f : nonEmptyAttachments) {
            totalBytes += f.getSize();
        }
        if (totalBytes > maxFollowUpTotalBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The attached files are too large (maximum " + (maxFollowUpTotalBytes / (1024 * 1024))
                            + " MB in total). Please send fewer or smaller files.");
        }

        // Records only (no bytes yet): after the email is sent the files are read from the Gmail
        // thread; see linkSentAttachments below.
        List<Attachment> savedAttachments = new ArrayList<>();
        if (!nonEmptyAttachments.isEmpty()) {
            savedAttachments = fileStorageService.storePlaceholders(nonEmptyAttachments, performedBy);
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

        // WHO sends this email?
        //  - The RFI creator replying/resubmitting: the Gmail thread is in THEIR mailbox,
        //    so it is sent from their account into that thread (unchanged behaviour).
        //  - Anyone else (e.g. a CC person): the email is sent from THAT PERSON'S OWN
        //    connected Gmail, so Gmail's From header, the stored From address and the
        //    timeline all show the person who actually replied — not the RFI creator.
        String threadOwner = (rfi.getSentBy() != null && !rfi.getSentBy().isBlank())
                ? rfi.getSentBy() : performedBy;
        boolean replierIsThreadOwner = performedBy != null
                && performedBy.trim().equalsIgnoreCase(threadOwner.trim());
        String sendAsUser = replierIsThreadOwner ? threadOwner : performedBy;

        SenderCredentials creds;
        try {
            creds = resolveSenderCredentials(sendAsUser);
        } catch (ResponseStatusException e) {
            for (Attachment a : savedAttachments) {
                attachmentRepository.delete(a);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    replierIsThreadOwner
                            ? "This RFI's email thread belongs to " + threadOwner + "'s Gmail account, so the "
                                    + "follow-up must be sent from it. " + e.getReason()
                            : "Your reply is sent from your own Gmail account. " + e.getReason());
        }

        // Which Gmail thread / message to reply into, in the SENDING account's mailbox.
        // Gmail thread ids are per mailbox: the creator's thread id means nothing in a CC
        // person's mailbox, so for them the same RFI email is found by its unique subject.
        String threadIdToUse = rfi.getGmailThreadId();
        String anchorGmailMessageId = lastGmailMessageId;
        if (!replierIsThreadOwner) {
            anchorGmailMessageId = null; // newest message of the thread in THEIR mailbox
            try {
                List<String> found = emailService.searchThreadIdsBySubject(
                        rfi.getGeneratedEmailSubject(), creds.refreshToken, creds.clientId, creds.clientSecret);
                if (found.size() == 1) {
                    threadIdToUse = found.get(0);
                } else {
                    for (Attachment a : savedAttachments) {
                        attachmentRepository.delete(a);
                    }
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            found.isEmpty()
                                    ? "This RFI's email was not found in your connected Gmail (" + creds.fromEmail
                                            + "). The Gmail connected in RFI Settings must be the address this RFI was CC'd to."
                                    : "More than one Gmail thread in your mailbox matches this RFI's subject, "
                                            + "so the reply was not sent to avoid replying in the wrong thread.");
                }
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                for (Attachment a : savedAttachments) {
                    attachmentRepository.delete(a);
                }
                throw RfiErrors.build(HttpStatus.BAD_GATEWAY, "Could not look up this RFI in your Gmail", e);
            }
        }

        // Recipients. For the creator: same as before (To = recipient, CC = original CC list).
        // For anyone else: the reply goes to everyone on the RFI EXCEPT the replier, and the
        // RFI creator is included so the reply also lands in the creator's mailbox / sync.
        String toAddress = rfi.getRecipientEmail();
        List<String> ccForSend = new ArrayList<>(ccList);
        if (!replierIsThreadOwner) {
            String replierNorm = RfiAccessService.normalizeEmail(creds.fromEmail);
            java.util.Map<String, String> others = new java.util.LinkedHashMap<>();
            if (replierNorm != null && replierNorm.equals(RfiAccessService.normalizeEmail(toAddress))) {
                toAddress = rfi.getSenderEmail();            // the "To" person replying -> goes back to the creator
            } else if (rfi.getSenderEmail() != null) {
                others.put(String.valueOf(RfiAccessService.normalizeEmail(rfi.getSenderEmail())), rfi.getSenderEmail());
            }
            for (String cc : ccList) {
                others.putIfAbsent(String.valueOf(RfiAccessService.normalizeEmail(cc)), cc);
            }
            String toNorm = RfiAccessService.normalizeEmail(toAddress);
            others.remove(String.valueOf(replierNorm));
            others.remove(String.valueOf(toNorm));
            ccForSend = new ArrayList<>(others.values());
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

            // GmailEmailService turns threadId + anchor message id into the real
            // In-Reply-To / References / Subject so the reply lands in the same thread.
            sendResult = emailService.sendReplyInThread(
                    toAddress,
                    replySubject,
                    message,
                    bytesList,
                    filenames,
                    contentTypes,
                    replierIsThreadOwner ? rfi.getSenderEmail() : null,   // Reply-To: only for the creator
                    ccForSend,
                    threadIdToUse,
                    anchorGmailMessageId,
                    creds.refreshToken,
                    creds.fromEmail,
                    replierIsThreadOwner ? rfi.getSenderName() : sendAsUser,   // From display name
                    creds.clientId,
                    creds.clientSecret
            );
        } catch (Exception e) {
            for (Attachment a : savedAttachments) {
                attachmentRepository.delete(a);
            }
            throw RfiErrors.build(HttpStatus.BAD_GATEWAY, "Failed to send the follow-up email", e);
        }

        // ── The email is out: point each attachment at its copy in the Gmail thread ──
        // The sent message sits in the SENDING account's mailbox (sendAsUser). Never throws; any
        // file that can't be found there is kept in the database instead.
        if (!savedAttachments.isEmpty()) {
            gmailAttachmentService.linkSentAttachments(
                    savedAttachments, nonEmptyAttachments, sendResult.getMessageId(),
                    sendAsUser, creds.refreshToken, creds.clientId, creds.clientSecret);
        }

        // ── Verify thread ID wasn't lost (should still be the SAME) ──
        String resultThreadId = sendResult.getThreadId();
        if (!threadIdToUse.equals(resultThreadId)) {
            // The email is already sent, so don't throw (a retry would send a duplicate).
            log.error("Thread ID mismatch after follow-up on RFI {}! Expected: {}, Got: {}. "
                    + "Gmail created a new thread instead of replying.",
                    rfi.getRfiNumber(), threadIdToUse, resultThreadId);
        }

        // The CC person's Gmail has the same email under a different Gmail id; the Message-ID
        // header is the same everywhere, so remember it and the creator's sync skips it instead
        // of importing a duplicate. (A Gmail call - done BEFORE the database transaction opens.)
        final String rfcMessageId = replierIsThreadOwner ? null : emailService.fetchRfcMessageId(
                sendResult.getMessageId(), creds.refreshToken, creds.clientId, creds.clientSecret);

        // ── Everything the email changed in the database, in ONE short transaction ──
        final String historyNote = savedAttachments.isEmpty()
                ? noteNoAttachments
                : String.format(noteWithAttachmentsFormat, savedAttachments.size());
        final List<Attachment> attachmentsToLink = new ArrayList<>(savedAttachments);
        final String toForRecord = toAddress;
        final List<String> ccForRecord = new ArrayList<>(ccForSend);
        final String replySubjectFinal = replySubject;
        final String fromForRecord = replierIsThreadOwner ? rfi.getSenderEmail() : creds.fromEmail;
        final boolean ownerReply = replierIsThreadOwner;

        return this.<Rfi>persistAfterSend("follow-up on RFI " + rfi.getRfiNumber(), sendResult, () -> tx().<Rfi>execute(status -> {
            // Re-read inside the transaction: the RFI loaded above is detached by now.
            Rfi managed = rfiRepository.findById(rfiId)
                    .orElseThrow(() -> new ResourceNotFoundException("RFI not found with ID: " + rfiId));

            // Do NOT overwrite the thread ID - it should remain the ORIGINAL one
            // (only set if somehow it was null initially, which shouldn't happen)
            if (managed.getGmailThreadId() == null || managed.getGmailThreadId().isBlank()) {
                managed.setGmailThreadId(resultThreadId);
            }

            // Status + history + notifications in the one shared place. The history
            // row is written even if the status is already the target, so every
            // reply/resubmit shows on the timeline.
            Rfi saved = rfiStatusTransitionService.transition(
                    managed, targetStatus, performedBy, actorRole, RfiActionSource.APP, historyNote, true);

            RfiEmailMessage outgoing = new RfiEmailMessage();
            outgoing.setRfi(saved);
            outgoing.setDirection(RfiEmailDirection.OUTGOING);
            outgoing.setGmailMessageId(sendResult.getMessageId());
            // Keep the RFI's own thread id on the record even when a CC person's mailbox used another.
            outgoing.setGmailThreadId(ownerReply ? resultThreadId : managed.getGmailThreadId());
            // The address that REALLY sent it: the creator's, or the CC person's own Gmail.
            outgoing.setFromAddress(fromForRecord);
            outgoing.setToAddress(toForRecord);
            outgoing.setCcAddress(ccForRecord.isEmpty() ? null : String.join(", ", ccForRecord));
            outgoing.setRfcMessageId(rfcMessageId);
            outgoing.setSubject(replySubjectFinal);
            outgoing.setBodyText(EmailBodyCleaner.cap(message, storedBodyMaxChars));
            outgoing.setSnippet(message.length() > 200 ? message.substring(0, 200) : message);
            outgoing.setMessageDate(LocalDateTime.now());
            outgoing.getAttachments().addAll(attachmentsToLink);
            rfiEmailMessageRepository.save(outgoing);

            return saved;
        }));
    }

    private static final int MAX_STARTING_RFI_NUMBER = 99999;

    private void validateStartingNumber(Integer startingNumber) {
        if (startingNumber != null && (startingNumber < 1 || startingNumber > MAX_STARTING_RFI_NUMBER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Starting RFI number must be between 1 and " + MAX_STARTING_RFI_NUMBER);
        }
    }

    /**
     * Next RFI number for a project.
     *  - Project has no RFIs yet: use {@code startingNumber} if the user supplied one, else 1
     *    (the original behaviour).
     *  - Project already has RFIs: {@code startingNumber} is ignored; continue from the
     *    highest number issued so far (so a project that started at 50 goes 50, 51, 52 ...).
     */
    private String nextRfiNumber(String projectName, Integer startingNumber) {
        List<String> existing = rfiRepository.findRfiNumbersByProjectName(projectName);
        if (existing.isEmpty()) {
            int first = (startingNumber != null) ? startingNumber : 1;
            return String.format("RFI#%03d", first);
        }
        long highest = existing.size();
        for (String n : existing) {
            if (n == null) continue;
            String digits = n.replaceAll("\\D+", "");
            if (digits.isEmpty() || digits.length() > 9) continue;
            highest = Math.max(highest, Long.parseLong(digits));
        }
        return String.format("RFI#%03d", highest + 1);
    }
}