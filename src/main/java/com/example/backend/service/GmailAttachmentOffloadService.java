package com.example.backend.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.entity.Attachment;
import com.example.backend.entity.RfiEmailMessage;
import com.example.backend.repository.RfiEmailMessageRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * One-time clean-up of files that were copied into the database BEFORE the app started reading
 * email files straight from Gmail. It frees the storage those old LONGBLOBs use.
 *
 * OFF by default. Turn it on with the environment variable ATTACHMENT_OFFLOAD_MODE:
 *   off      nothing happens (default)
 *   dry-run  walks through the data and only LOGS what it would free - changes nothing
 *   delete   for each stored email file: finds it in the Gmail message, downloads it from Gmail
 *            once to CHECK it has exactly the same size, and only then points the record at Gmail
 *            and deletes the stored bytes. Any file that cannot be verified is left untouched.
 *
 * It makes one pass per application start (small batches, a few minutes apart) and then stops.
 * Files that were sent from a teammate's own Gmail (their mailbox is not the RFI owner's) or whose
 * email was deleted in Gmail cannot be found and simply stay in the database.
 *
 * Note: MySQL does not hand freed space back to the disk by itself. After a "delete" pass has
 * finished, run   OPTIMIZE TABLE attachment_data;   once to shrink the database file.
 */
@Service
@Slf4j
public class GmailAttachmentOffloadService {

    @Value("${app.attachments.offload.mode:off}")
    private String mode;

    @Value("${app.attachments.offload.batch-size:20}")
    private int batchSize;

    @Autowired
    private RfiEmailMessageRepository emailMessageRepository;

    @Autowired
    private GmailAttachmentService gmailAttachmentService;

    @Autowired
    private GmailEmailService emailService;

    @Autowired
    private FileStorageService fileStorageService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long cursor = 0L;
    private volatile boolean finished = false;

    // Totals for this application run (only touched by the single scheduler thread).
    private long messagesSeen;
    private long messagesSkipped;
    private long filesDone;
    private long bytesDone;
    private long filesNotFound;
    private long filesMismatch;

    @Scheduled(initialDelayString = "${app.attachments.offload.initial-delay-ms:120000}",
               fixedDelayString = "${app.attachments.offload.interval-ms:300000}")
    public void run() {
        String m = mode == null ? "off" : mode.trim().toLowerCase(Locale.ROOT);
        if (finished || m.equals("off") || m.isEmpty()) {
            return;
        }
        boolean delete = m.equals("delete");
        if (!delete && !m.equals("dry-run")) {
            log.warn("Attachment offload: unknown mode '{}' (valid: off, dry-run, delete); doing nothing", mode);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runBatch(delete);
        } catch (Exception e) {
            log.warn("Attachment offload batch failed: {}", e.toString());
        } finally {
            running.set(false);
        }
    }

    private void runBatch(boolean delete) {
        List<Long> ids = emailMessageRepository.findIdsWithStoredAttachmentsAfter(
                cursor, PageRequest.of(0, Math.max(1, batchSize)));
        if (ids.isEmpty()) {
            finished = true;
            log.info("Attachment offload [{}] finished: {} messages looked at, {} files {} (~{} MB), "
                            + "{} messages skipped (mailbox not reachable), {} files not found in Gmail, "
                            + "{} files failed the size check.{}",
                    delete ? "DELETE" : "DRY RUN", messagesSeen, filesDone,
                    delete ? "moved to Gmail" : "could be moved to Gmail",
                    bytesDone / (1024 * 1024), messagesSkipped, filesNotFound, filesMismatch,
                    delete ? " Run  OPTIMIZE TABLE attachment_data;  once to shrink the database file."
                           : " Set ATTACHMENT_OFFLOAD_MODE=delete to actually free the space.");
            return;
        }
        for (Long id : ids) {
            messagesSeen++;
            try {
                processMessage(id, delete);
            } catch (Exception e) {
                log.warn("Attachment offload: message {} skipped: {}", id, e.toString());
                messagesSkipped++;
            }
            cursor = id; // never look at the same message twice in one run, even if it was skipped
        }
    }

    private void processMessage(Long messageId, boolean delete) {
        RfiEmailMessage msg = emailMessageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return;
        }
        List<Attachment> todo = new ArrayList<>();
        for (Attachment a : msg.getAttachments()) {
            if (!a.isGmailBacked()) {
                todo.add(a);
            }
        }
        if (todo.isEmpty()) {
            return;
        }

        String account = emailMessageRepository.findSentByOfMessage(messageId).orElse(null);
        GmailAttachmentService.MailboxCredentials creds;
        try {
            creds = gmailAttachmentService.credentialsFor(account);
        } catch (ResponseStatusException e) {
            messagesSkipped++;
            return;
        }

        List<GmailFetchedAttachment> inGmail;
        try {
            inGmail = emailService.listMessageAttachments(
                    msg.getGmailMessageId(), creds.refreshToken, creds.clientId, creds.clientSecret);
        } catch (Exception e) {
            // Typically: the message sits in another person's mailbox, or was deleted in Gmail.
            messagesSkipped++;
            return;
        }

        List<GmailFetchedAttachment> unused = new ArrayList<>(inGmail);
        for (Attachment a : todo) {
            GmailFetchedAttachment part = GmailAttachmentService.takeMatch(unused, a);
            if (part == null) {
                filesNotFound++;
                continue;
            }
            if (!sameSizeInGmail(msg.getGmailMessageId(), part, a, creds)) {
                filesMismatch++;
                continue;
            }
            if (delete) {
                fileStorageService.linkToGmail(a, msg.getGmailMessageId(), part.getGmailAttachmentId(),
                        part.getPartId(), creds.username);
                fileStorageService.deleteStoredBytes(a.getId());
            }
            filesDone++;
            bytesDone += a.getFileSize() != null ? a.getFileSize() : 0L;
        }
    }

    /** Reads the file once from Gmail (streamed, nothing kept in memory) and compares its size. */
    private boolean sameSizeInGmail(String gmailMessageId, GmailFetchedAttachment part, Attachment a,
                                    GmailAttachmentService.MailboxCredentials creds) {
        try (GmailEmailService.AttachmentContent content = emailService.openAttachment(
                gmailMessageId, part.getGmailAttachmentId(), part.getPartId(), a.getOriginalName(), a.getFileSize(),
                creds.refreshToken, creds.clientId, creds.clientSecret)) {
            long bytes = content.writeTo(new CountingSink());
            return a.getFileSize() != null && bytes == a.getFileSize().longValue();
        } catch (Exception e) {
            return false;
        }
    }

    /** An OutputStream that only counts the bytes written to it. */
    private static final class CountingSink extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            // not used by readBinaryValue's bulk writes, kept for completeness
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // bytes are discarded; the caller uses writeTo's return value as the count
        }
    }
}