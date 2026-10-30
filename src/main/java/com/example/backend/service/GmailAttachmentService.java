package com.example.backend.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.backend.entity.Attachment;
import com.example.backend.entity.User;
import com.example.backend.repository.AttachmentRepository;
import com.example.backend.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Lets the app View / Download a file DIRECTLY FROM THE GMAIL THREAD instead of keeping a copy in
 * the database.
 *
 * - open(...)                 : used by AttachmentController. Streams one file out of Gmail.
 * - linkSentAttachments(...)  : used right after the app sends an email. Remembers where Gmail
 *                               keeps each sent file (and drops our own copy).
 *
 * Every Gmail request is made as the mailbox that really holds the message (Attachment.gmailAccount,
 * using that user's token from RFI Settings), never as the person clicking the button.
 */
@Service
@Slf4j
public class GmailAttachmentService {

    @Autowired
    private GmailEmailService emailService;

    @Autowired
    private RfiSettingsService rfiSettingsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private FileStorageService fileStorageService;

    /** Token + OAuth client + address of one connected Gmail mailbox. */
    public static final class MailboxCredentials {
        public final String username;
        public final String refreshToken;
        public final String clientId;
        public final String clientSecret;
        public final String gmailAddress;

        MailboxCredentials(String username, String refreshToken, String clientId, String clientSecret, String gmailAddress) {
            this.username = username;
            this.refreshToken = refreshToken;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.gmailAddress = gmailAddress;
        }
    }

    /**
     * Credentials of the Gmail account connected by {@code username} in RFI Settings.
     * @throws ResponseStatusException (502) when that account is not (or no longer) connected
     */
    public MailboxCredentials credentialsFor(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "This file lives in a Gmail mailbox, but it is not recorded whose mailbox it is");
        }
        String name = username.trim();
        User user = userRepository.findByUsername(name).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "The Gmail account that holds this file (" + name + ") no longer exists"));
        if (!rfiSettingsService.hasValidSettings(user.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    name + " needs to reconnect Gmail in RFI Settings before this file can be opened");
        }
        return new MailboxCredentials(
                name,
                rfiSettingsService.getDecryptedToken(user.getId()),
                rfiSettingsService.getGmailClientId(user.getId()),
                rfiSettingsService.getDecryptedClientSecret(user.getId()),
                rfiSettingsService.getGmailEmail(user.getId()));
    }

    // ── View / Download ──────────────────────────────────────────────────────────────────────

    /**
     * Opens the Gmail copy of a Gmail-backed attachment. The caller streams it to the browser with
     * {@code content.writeTo(out)} and then closes it. Errors are turned into HTTP statuses here so
     * they happen BEFORE any byte is sent to the browser.
     */
    public GmailEmailService.AttachmentContent open(Attachment attachment) {
        MailboxCredentials creds = credentialsFor(attachment.getGmailAccount());
        try {
            GmailEmailService.AttachmentContent content = emailService.openAttachment(
                    attachment.getGmailMessageId(), attachment.getGmailAttachmentId(),
                    attachment.getGmailPartId(), attachment.getOriginalName(), attachment.getFileSize(),
                    creds.refreshToken, creds.clientId, creds.clientSecret);
            rememberRefreshedId(attachment, content.getAttachmentIdUsed());
            return content;
        } catch (FileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            log.warn("Could not open Gmail attachment {} (message {}): {}", attachment.getId(),
                    attachment.getGmailMessageId(), msg);
            if (msg.toLowerCase().contains("invalid_grant")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Google rejected the Gmail connection of " + creds.username
                                + ". They need to reconnect Gmail in RFI Settings.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not fetch the file from Gmail right now. Please try again.");
        }
    }

    /** If Gmail gave the file a new attachment id, keep it so the next view is one request shorter. */
    private void rememberRefreshedId(Attachment attachment, String usedId) {
        if (usedId == null || usedId.equals(attachment.getGmailAttachmentId())) {
            return;
        }
        try {
            attachment.setGmailAttachmentId(usedId);
            attachmentRepository.save(attachment);
        } catch (Exception e) {
            log.debug("Could not save refreshed Gmail attachment id for attachment {}: {}", attachment.getId(), e.getMessage());
        }
    }

    // ── After the app sends an email ─────────────────────────────────────────────────────────

    /**
     * Call right after an email with attachments was sent. {@code saved} are the placeholder
     * attachments (see FileStorageService.storePlaceholders) and {@code files} the uploaded files
     * in the SAME ORDER. For each file this finds it in the SENT Gmail message and links the
     * attachment to it, so no copy is kept in the database.
     *
     * If a file cannot be found in the sent message (Gmail hiccup, odd file name...), its bytes are
     * stored in the database instead, so a file is never lost. The email is already out at this
     * point, so this method never throws.
     */
    public void linkSentAttachments(List<Attachment> saved, List<MultipartFile> files, String gmailMessageId,
                                    String account, String refreshToken, String clientId, String clientSecret) {
        List<GmailFetchedAttachment> unused = new ArrayList<>();
        try {
            if (gmailMessageId != null && !gmailMessageId.isBlank()) {
                unused.addAll(emailService.listMessageAttachments(gmailMessageId, refreshToken, clientId, clientSecret));
            }
        } catch (Exception e) {
            log.warn("Could not read the sent Gmail message {} to link its files; keeping them in the database: {}",
                    gmailMessageId, e.getMessage());
        }

        for (int i = 0; i < saved.size(); i++) {
            Attachment a = saved.get(i);
            GmailFetchedAttachment match = takeMatch(unused, a);
            try {
                if (match != null) {
                    fileStorageService.linkToGmail(a, gmailMessageId, match.getGmailAttachmentId(),
                            match.getPartId(), account != null ? account.trim() : null);
                } else {
                    fileStorageService.storeBytesFor(a, files.get(i).getBytes());
                }
            } catch (IOException | RuntimeException e) {
                log.error("Could not record attachment '{}' (id={}) after sending: {}",
                        a.getOriginalName(), a.getId(), e.getMessage());
            }
        }
    }

    /** Picks (and removes) the Gmail file that corresponds to {@code a}: same name + size, else unique name. */
    static GmailFetchedAttachment takeMatch(List<GmailFetchedAttachment> unused, Attachment a) {
        GmailFetchedAttachment sameNameOnly = null;
        int sameNameCount = 0;
        for (GmailFetchedAttachment p : unused) {
            if (p.getFilename() != null && p.getFilename().equalsIgnoreCase(a.getOriginalName())) {
                if (p.getSize() != null && a.getFileSize() != null && p.getSize().longValue() == a.getFileSize().longValue()) {
                    unused.remove(p);
                    return p;
                }
                sameNameCount++;
                sameNameOnly = p;
            }
        }
        if (sameNameCount == 1) {
            unused.remove(sameNameOnly);
            return sameNameOnly;
        }
        return null;
    }
}