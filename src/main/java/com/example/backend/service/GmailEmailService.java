package com.example.backend.service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.google.api.services.gmail.model.MessagePartBody;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.Thread;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Gmail API service for sending and fetching RFI emails.
 * Handles OAuth 2.0 authentication and Gmail API operations.
 */
@Service
@Slf4j
public class GmailEmailService {

    // These application.properties values are now only a LEGACY fallback —
    // used only if a caller doesn't pass its own senderClientId/
    // senderClientSecret (see sendWithAttachment/sendReplyInThread below).
    // Every normal RFI send now supplies the ACTING USER's own Client ID/
    // Secret from their RFI Settings (RfiSettings.gmailClientId /
    // gmailClientSecret), so application.properties no longer needs to be
    // configured at all for RFI sending to work.
    @Value("${gmail.client-id:}")
    private String clientId;

    @Value("${gmail.client-secret:}")
    private String clientSecret;

    @Value("${gmail.refresh-token:}")
    private String refreshToken;

    @Value("${gmail.from-email:}")
    private String fromEmail;

    @Value("${gmail.from-name:RFI Notifications}")
    private String fromName;

    // ── Sending ──────────────────────────────────────────────────────────

        /**
     * Sends a plain-text email with a single attachment via Gmail API.
     * Returns the sent message ID and thread ID for tracking.
     * 
     * @param toEmail recipient email
     * @param subject email subject
     * @param bodyText email body
     * @param attachmentBytes file bytes
     * @param attachmentFilename filename
     * @param attachmentContentType MIME type
     * @param replyToEmail optional Reply-To address
     * @param ccEmails optional list of CC recipient addresses (may be null/empty)
     * @param senderRefreshToken the OAuth refresh token for the account this RFI should
     *                           actually be SENT FROM — i.e. the current user's own
     *                           connected Gmail account (RfiSettings.gmailRefreshToken,
     *                           decrypted). Pass null/blank to fall back to the single
     *                           globally-configured GMAIL_REFRESH_TOKEN (legacy behavior).
     * @param senderFromEmail the Gmail address matching senderRefreshToken. Pass
     *                        null/blank to fall back to GMAIL_FROM_EMAIL.
     * @param senderFromName the display name to show on the "From" header —
     *                       i.e. the ACTING USER's own name (e.g. "Jane Doe"),
     *                       not one name shared by everyone. Pass null/blank
     *                       to fall back to the single globally-configured
     *                       GMAIL_FROM_NAME (legacy behavior).
     * @param senderClientId the OAuth Client ID from the acting user's OWN
     *                       RFI Settings (RfiSettings.gmailClientId). Pass
     *                       null/blank to fall back to the legacy global
     *                       GMAIL_CLIENT_ID.
     * @param senderClientSecret the OAuth Client Secret matching
     *                           senderClientId (RfiSettings.gmailClientSecret,
     *                           decrypted). Pass null/blank to fall back to
     *                           the legacy global GMAIL_CLIENT_SECRET.
     * @return GmailSendResult with message ID and thread ID
     */
    public GmailSendResult sendWithAttachment(
            String toEmail,
            String subject,
            String bodyText,
            byte[] attachmentBytes,
            String attachmentFilename,
            String attachmentContentType,
            String replyToEmail,
            List<String> ccEmails,
            String senderRefreshToken,
            String senderFromEmail,
            String senderFromName,
            String senderClientId,
            String senderClientSecret
    ) throws Exception {
        // Per-user token/email/name/client-id/client-secret take priority;
        // only fall back to the single global application.properties values
        // when the caller didn't supply its own (keeps any other,
        // not-yet-updated caller working exactly as before).
        String tokenToUse = (senderRefreshToken != null && !senderRefreshToken.isBlank())
                ? senderRefreshToken : refreshToken;
        String fromToUse = (senderFromEmail != null && !senderFromEmail.isBlank())
                ? senderFromEmail : fromEmail;
        String fromNameToUse = (senderFromName != null && !senderFromName.isBlank())
                ? senderFromName : fromName;
        String clientIdToUse = (senderClientId != null && !senderClientId.isBlank())
                ? senderClientId : clientId;
        String clientSecretToUse = (senderClientSecret != null && !senderClientSecret.isBlank())
                ? senderClientSecret : clientSecret;

        if (clientIdToUse == null || clientIdToUse.isBlank()
                || clientSecretToUse == null || clientSecretToUse.isBlank()) {
            throw new Exception("Gmail API OAuth client is not configured — add your Client ID "
                    + "and Client Secret in RFI Settings");
        }
        if (tokenToUse == null || tokenToUse.isBlank()) {
            throw new Exception("No Gmail refresh token available to send with — neither a "
                    + "per-user token nor the fallback GMAIL_REFRESH_TOKEN is set");
        }
        if (fromToUse == null || fromToUse.isBlank()) {
            throw new Exception("No Gmail sender address available — neither a per-user "
                    + "Gmail email nor the fallback GMAIL_FROM_EMAIL is set");
        }

        Gmail gmail = buildGmailService(tokenToUse, clientIdToUse, clientSecretToUse);

        MimeMessage mimeMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        mimeMessage.setFrom(new InternetAddress(fromToUse, fromNameToUse));
        mimeMessage.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(toEmail));
        mimeMessage.setSubject(subject, "UTF-8");
        if (replyToEmail != null && !replyToEmail.isBlank()) {
            mimeMessage.setReplyTo(new InternetAddress[]{new InternetAddress(replyToEmail)});
        }
        if (ccEmails != null) {
            for (String cc : ccEmails) {
                if (cc != null && !cc.isBlank()) {
                    mimeMessage.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc.trim()));
                }
            }
        }

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(bodyText, "UTF-8");

        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setDataHandler(new jakarta.activation.DataHandler(
                new ByteArrayDataSource(attachmentBytes,
                        attachmentContentType != null ? attachmentContentType : "application/pdf")));
        attachmentPart.setFileName(attachmentFilename);

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachmentPart);
        mimeMessage.setContent(multipart);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        String encodedEmail = Base64.getUrlEncoder().encodeToString(buffer.toByteArray());

        Message message = new Message();
        message.setRaw(encodedEmail);

        try {
            Message sentMessage = gmail.users().messages().send("me", message).execute();
            log.info("Email sent successfully. Message ID: {}, Thread ID: {}", 
                    sentMessage.getId(), sentMessage.getThreadId());
            
            return new GmailSendResult(sentMessage.getId(), sentMessage.getThreadId());
        } catch (Exception e) {
            log.error("Gmail API error sending message: {}", e.getMessage());
            throw new Exception("Gmail API error: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a reply into an EXISTING Gmail thread (used for RFI resubmit / reopen).
     *
     * HOW GMAIL DECIDES WHAT THREAD A MESSAGE BELONGS TO
     * Passing threadId is NOT enough on its own. Gmail only accepts the threadId if
     *   (a) the RFC 822 "In-Reply-To" / "References" headers point at a REAL
     *       "Message-ID" header that exists in that thread, AND
     *   (b) the Subject matches the thread's subject (ignoring Re:/Fwd: prefixes).
     * Otherwise it silently starts a brand-new thread.
     *
     * The old code built In-Reply-To as "<" + gmailInternalId + "@mail.gmail.com>".
     * Gmail's internal id (e.g. 18c3f9a...) is NOT the Message-ID header, so that
     * value never matched anything. This version therefore reads the real headers
     * (Message-ID, References, Subject) from the thread via the Gmail API and
     * builds the reply from those.
     *
     * @param inReplyToGmailMessageId Gmail INTERNAL id of the message being replied to
     *                                (RfiEmailMessage.gmailMessageId). May be null/blank
     *                                or not found in the thread - then the LAST message
     *                                of the thread is used.
     * @param gmailThreadId the existing Gmail thread id (required)
     * @param subject       only used as a fallback if the thread's own subject can't be read
     *                      (the thread's real subject always wins, so grouping is guaranteed)
     * @return GmailSendResult with the new message id and the thread id Gmail put it in
     */
    public GmailSendResult sendReplyInThread(
            String toEmail,
            String subject,
            String bodyText,
            List<byte[]> attachmentBytesList,
            List<String> attachmentFilenames,
            List<String> attachmentContentTypes,
            String replyToEmail,
            List<String> ccEmails,
            String gmailThreadId,
            String inReplyToGmailMessageId,
            String senderRefreshToken,
            String senderFromEmail,
            String senderFromName,
            String senderClientId,
            String senderClientSecret
    ) throws Exception {
        if (gmailThreadId == null || gmailThreadId.isBlank()) {
            throw new Exception("Cannot reply in thread: Gmail thread id is missing");
        }

        String tokenToUse = (senderRefreshToken != null && !senderRefreshToken.isBlank())
                ? senderRefreshToken : refreshToken;
        String fromToUse = (senderFromEmail != null && !senderFromEmail.isBlank())
                ? senderFromEmail : fromEmail;
        String fromNameToUse = (senderFromName != null && !senderFromName.isBlank())
                ? senderFromName : fromName;
        String clientIdToUse = (senderClientId != null && !senderClientId.isBlank())
                ? senderClientId : clientId;
        String clientSecretToUse = (senderClientSecret != null && !senderClientSecret.isBlank())
                ? senderClientSecret : clientSecret;

        if (clientIdToUse == null || clientIdToUse.isBlank()
                || clientSecretToUse == null || clientSecretToUse.isBlank()) {
            throw new Exception("Gmail API OAuth client is not configured — add your Client ID "
                    + "and Client Secret in RFI Settings");
        }
        if (tokenToUse == null || tokenToUse.isBlank()) {
            throw new Exception("No Gmail refresh token available to send with — neither a "
                    + "per-user token nor the fallback GMAIL_REFRESH_TOKEN is set");
        }
        if (fromToUse == null || fromToUse.isBlank()) {
            throw new Exception("No Gmail sender address available — neither a per-user "
                    + "Gmail email nor the fallback GMAIL_FROM_EMAIL is set");
        }

        Gmail gmail = buildGmailService(tokenToUse, clientIdToUse, clientSecretToUse);

        // ── 1. Read the REAL threading headers from the existing thread ────────
        ThreadReplyInfo info = loadThreadReplyInfo(gmail, gmailThreadId, inReplyToGmailMessageId);

        String finalSubject = (info.threadSubject != null && !info.threadSubject.isBlank())
                ? "Re: " + stripReplyPrefix(info.threadSubject)
                : subject;

        // ── 2. Build the MIME message ──────────────────────────────────────────
        MimeMessage mimeMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        mimeMessage.setFrom(new InternetAddress(fromToUse, fromNameToUse));
        mimeMessage.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(toEmail));
        mimeMessage.setSubject(finalSubject, "UTF-8");
        if (replyToEmail != null && !replyToEmail.isBlank()) {
            mimeMessage.setReplyTo(new InternetAddress[]{new InternetAddress(replyToEmail)});
        }
        if (ccEmails != null) {
            for (String cc : ccEmails) {
                if (cc != null && !cc.isBlank()) {
                    mimeMessage.addRecipient(jakarta.mail.Message.RecipientType.CC, new InternetAddress(cc.trim()));
                }
            }
        }

        // The two headers Gmail actually uses to thread — real Message-IDs, with angle brackets.
        mimeMessage.setHeader("In-Reply-To", info.inReplyToHeader);
        mimeMessage.setHeader("References", info.referencesHeader);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(bodyText, "UTF-8");

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);

        int attachmentCount = attachmentBytesList != null ? attachmentBytesList.size() : 0;
        for (int i = 0; i < attachmentCount; i++) {
            MimeBodyPart attachmentPart = new MimeBodyPart();
            String contentType = (attachmentContentTypes != null && i < attachmentContentTypes.size())
                    ? attachmentContentTypes.get(i) : null;
            attachmentPart.setDataHandler(new jakarta.activation.DataHandler(
                    new ByteArrayDataSource(attachmentBytesList.get(i),
                            contentType != null ? contentType : "application/octet-stream")));
            attachmentPart.setFileName(attachmentFilenames.get(i));
            multipart.addBodyPart(attachmentPart);
        }

        mimeMessage.setContent(multipart);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        String encodedEmail = Base64.getUrlEncoder().encodeToString(buffer.toByteArray());

        // ── 3. Send with the threadId + matching headers + matching subject ────
        Message message = new Message();
        message.setRaw(encodedEmail);
        message.setThreadId(gmailThreadId);

        try {
            Message sentMessage = gmail.users().messages().send("me", message).execute();
            log.info("Reply sent. Message ID: {}, Thread ID: {} (requested thread: {}), In-Reply-To: {}",
                    sentMessage.getId(), sentMessage.getThreadId(), gmailThreadId, info.inReplyToHeader);

            if (!gmailThreadId.equals(sentMessage.getThreadId())) {
                log.error("THREAD MISMATCH: asked for thread {} but Gmail placed the message in {}",
                        gmailThreadId, sentMessage.getThreadId());
            }

            return new GmailSendResult(sentMessage.getId(), sentMessage.getThreadId());
        } catch (Exception e) {
            log.error("Gmail API error sending reply: {}", e.getMessage());
            throw new Exception("Gmail API error: " + e.getMessage(), e);
        }
    }

    // ── Reply-threading helpers ──────────────────────────────────────────

    /** Real threading data read from an existing Gmail thread. */
    private static final class ThreadReplyInfo {
        String threadSubject;      // subject of the first message in the thread
        String inReplyToHeader;    // "<real-message-id@...>" of the message we reply to
        String referencesHeader;   // that message's References chain + its own Message-ID
    }

    /**
     * Fetches ONLY the headers we need (format=metadata) for every message in the
     * thread and derives In-Reply-To / References / Subject from the real data.
     */
    private ThreadReplyInfo loadThreadReplyInfo(Gmail gmail, String threadId, String anchorGmailId) throws Exception {
        Thread thread;
        try {
            thread = gmail.users().threads().get("me", threadId)
                    .setFormat("metadata")
                    .setMetadataHeaders(java.util.Arrays.asList("Message-ID", "References", "In-Reply-To", "Subject"))
                    .execute();
        } catch (Exception e) {
            throw new Exception("Could not read Gmail thread " + threadId + " to reply into it. "
                    + "The thread must exist in the mailbox of the account sending this reply "
                    + "(the account that originally sent the RFI). Cause: " + e.getMessage(), e);
        }

        List<Message> msgs = thread.getMessages();
        if (msgs == null || msgs.isEmpty()) {
            throw new Exception("Gmail thread " + threadId + " has no messages — cannot reply into it");
        }

        // Anchor = the message we're replying to (matched by Gmail internal id), else the newest one.
        Message anchor = msgs.get(msgs.size() - 1);
        if (anchorGmailId != null && !anchorGmailId.isBlank()) {
            for (Message m : msgs) {
                if (anchorGmailId.equals(m.getId())) {
                    anchor = m;
                    break;
                }
            }
        }

        String anchorMessageId = headerValue(anchor.getPayload(), "Message-ID");
        if (anchorMessageId == null) {
            throw new Exception("The message being replied to (" + anchor.getId()
                    + ") has no Message-ID header — cannot thread the reply");
        }
        anchorMessageId = toAngleId(anchorMessageId);

        // References = anchor's own References chain + anchor's Message-ID (RFC 5322 §3.6.4)
        String existingRefs = headerValue(anchor.getPayload(), "References");
        List<String> refIds = new ArrayList<>();
        if (existingRefs != null) {
            for (String token : existingRefs.split("\\s+")) {
                if (!token.isBlank() && !refIds.contains(token)) {
                    refIds.add(token);
                }
            }
        }
        if (!refIds.contains(anchorMessageId)) {
            refIds.add(anchorMessageId);
        }
        // Keep the header well under the 998-char line limit: first id + most recent 9.
        if (refIds.size() > 10) {
            List<String> trimmed = new ArrayList<>();
            trimmed.add(refIds.get(0));
            trimmed.addAll(refIds.subList(refIds.size() - 9, refIds.size()));
            refIds = trimmed;
        }

        ThreadReplyInfo info = new ThreadReplyInfo();
        info.inReplyToHeader = anchorMessageId;
        info.referencesHeader = String.join(" ", refIds);
        info.threadSubject = headerValue(msgs.get(0).getPayload(), "Subject");
        return info;
    }

    /** Case-insensitive header lookup; unfolds multi-line values. Null if absent/blank. */
    private static String headerValue(MessagePart payload, String name) {
        if (payload == null || payload.getHeaders() == null) {
            return null;
        }
        for (MessagePartHeader h : payload.getHeaders()) {
            if (name.equalsIgnoreCase(h.getName()) && h.getValue() != null && !h.getValue().isBlank()) {
                return h.getValue().replaceAll("\\s+", " ").trim();
            }
        }
        return null;
    }

    /** Ensures a Message-ID is wrapped in angle brackets. */
    private static String toAngleId(String id) {
        String t = id.trim();
        if (!t.startsWith("<")) {
            t = "<" + t;
        }
        if (!t.endsWith(">")) {
            t = t + ">";
        }
        return t;
    }

    /** "Re: Re: Fwd: X" -> "X" */
    private static String stripReplyPrefix(String subject) {
        return subject.replaceFirst("(?i)^\\s*((re|fw|fwd)\\s*:\\s*)+", "").trim();
    }

    // ── Fetching ─────────────────────────────────────────────────────────

    /**
     * Fetches all messages in a Gmail thread.
     * Used by RfiGmailSyncService to retrieve replies to an RFI email.
     * 
     * @param threadId Gmail thread ID
     * @return List of GmailFetchedMessage objects
     */
    /**
     * Fetches all messages in a Gmail thread, using the single legacy
     * globally-configured account. Kept only for any not-yet-updated
     * caller — RfiGmailSyncService now calls the per-user overload below.
     */
    public List<GmailFetchedMessage> fetchThreadMessages(String threadId) throws Exception {
        return fetchThreadMessages(threadId, refreshToken, clientId, clientSecret);
    }

    /**
     * Fetches all messages in a Gmail thread, authenticating as a SPECIFIC
     * account (that RFI's own sender's RFI Settings) rather than the
     * single global one. Falls back to the legacy global values for any
     * parameter left null/blank.
     *
     * @param threadId Gmail thread ID
     * @param refreshTokenToUse the sender's own OAuth refresh token
     * @param clientIdToUse the sender's own OAuth Client ID
     * @param clientSecretToUse the sender's own OAuth Client Secret
     * @return List of GmailFetchedMessage objects
     */
    public List<GmailFetchedMessage> fetchThreadMessages(
            String threadId, String refreshTokenToUse, String clientIdToUse, String clientSecretToUse) throws Exception {
        if (threadId == null || threadId.isBlank()) {
            return new ArrayList<>();
        }

        Gmail gmail = buildGmailService(
                (refreshTokenToUse != null && !refreshTokenToUse.isBlank()) ? refreshTokenToUse : refreshToken,
                (clientIdToUse != null && !clientIdToUse.isBlank()) ? clientIdToUse : clientId,
                (clientSecretToUse != null && !clientSecretToUse.isBlank()) ? clientSecretToUse : clientSecret);

        try {
            Thread thread = gmail.users().threads().get("me", threadId)
                    .setFormat("full")
                    .execute();

            List<GmailFetchedMessage> messages = new ArrayList<>();
            for (Message msg : thread.getMessages()) {
                GmailFetchedMessage fetchedMsg = parseGmailMessage(msg);
                messages.add(fetchedMsg);
            }

            log.debug("Fetched {} messages from thread {}", messages.size(), threadId);
            return messages;
        } catch (Exception e) {
            log.error("Error fetching Gmail thread {}: {}", threadId, e.getMessage());
            throw e;
        }
    }

    /**
     * Searches Gmail for threads matching a subject line.
     * Used as a fallback for RFIs that predate thread ID tracking.
     * 
     * @param subject email subject to search for
     * @return List of matching Gmail thread IDs
     */
    /**
     * Searches Gmail for threads matching a subject line, using the single
     * legacy globally-configured account. Kept only for any not-yet-updated
     * caller — RfiGmailSyncService now calls the per-user overload below.
     */
    public List<String> searchThreadIdsBySubject(String subject) throws Exception {
        return searchThreadIdsBySubject(subject, refreshToken, clientId, clientSecret);
    }

    /**
     * Searches Gmail for threads matching a subject line, authenticating as
     * a SPECIFIC account (that RFI's own sender's RFI Settings).
     * Used as a fallback for RFIs that predate thread ID tracking.
     * 
     * @param subject email subject to search for
     * @param refreshTokenToUse the sender's own OAuth refresh token
     * @param clientIdToUse the sender's own OAuth Client ID
     * @param clientSecretToUse the sender's own OAuth Client Secret
     * @return List of matching Gmail thread IDs
     */
    public List<String> searchThreadIdsBySubject(
            String subject, String refreshTokenToUse, String clientIdToUse, String clientSecretToUse) throws Exception {
        Gmail gmail = buildGmailService(
                (refreshTokenToUse != null && !refreshTokenToUse.isBlank()) ? refreshTokenToUse : refreshToken,
                (clientIdToUse != null && !clientIdToUse.isBlank()) ? clientIdToUse : clientId,
                (clientSecretToUse != null && !clientSecretToUse.isBlank()) ? clientSecretToUse : clientSecret);
        List<String> threadIds = new ArrayList<>();

        try {
            String query = "subject:\"" + escapeGmailSearchQuery(subject) + "\"";
            com.google.api.services.gmail.model.ListThreadsResponse response = 
                    gmail.users().threads().list("me")
                            .setQ(query)
                            .setMaxResults(10L)
                            .execute();

            if (response.getThreads() != null) {
                for (Thread thread : response.getThreads()) {
                    threadIds.add(thread.getId());
                }
            }

            log.debug("Subject search for '{}' returned {} thread(s)", subject, threadIds.size());
            return threadIds;
        } catch (Exception e) {
            log.error("Error searching Gmail for subject '{}': {}", subject, e.getMessage());
            throw e;
        }
    }

    /** Just the ids of a Gmail message — nothing is downloaded. */
    public record MessageRef(String messageId, String threadId) {
    }

    /**
     * Cheap "anything new?" probe used by the automatic reply check: ONE Gmail
     * call that returns only message/thread ids of mail NOT sent by this account
     * since the given time. No bodies, no attachments, no per-thread fetch.
     */
    public List<MessageRef> listRecentIncomingRefs(
            String refreshTokenToUse, String clientIdToUse, String clientSecretToUse,
            long afterEpochSeconds, int max) throws Exception {
        Gmail gmail = buildGmailService(
                (refreshTokenToUse != null && !refreshTokenToUse.isBlank()) ? refreshTokenToUse : refreshToken,
                (clientIdToUse != null && !clientIdToUse.isBlank()) ? clientIdToUse : clientId,
                (clientSecretToUse != null && !clientSecretToUse.isBlank()) ? clientSecretToUse : clientSecret);

        // Page through the results (Gmail returns max 100 per page, newest first) so a busy
        // inbox cannot push a real reply out of the window and make it get skipped.
        List<MessageRef> refs = new ArrayList<>();
        String pageToken = null;
        do {
            com.google.api.services.gmail.model.ListMessagesResponse response = gmail.users().messages().list("me")
                    .setQ("-from:me after:" + afterEpochSeconds)
                    .setMaxResults((long) Math.min(100, Math.max(1, max - refs.size())))
                    .setPageToken(pageToken)
                    .execute();
            if (response.getMessages() != null) {
                for (Message m : response.getMessages()) {
                    refs.add(new MessageRef(m.getId(), m.getThreadId()));
                }
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null && refs.size() < max);
        return refs;
    }

    /**
     * Fetches the full attachment bytes from a Gmail message.
     * Used by RfiGmailSyncService to download attachments from incoming replies.
     * 
     * @param messageId Gmail message ID
     * @param attachmentId Gmail attachment ID
     * @return The raw attachment bytes
     */
    /**
     * Fetches the full attachment bytes from a Gmail message, using the
     * single legacy globally-configured account. Kept only for any
     * not-yet-updated caller — RfiGmailSyncService now calls the per-user
     * overload below.
     */
    public byte[] fetchAttachmentBytes(String messageId, String attachmentId) throws Exception {
        return fetchAttachmentBytes(messageId, attachmentId, refreshToken, clientId, clientSecret);
    }

    /**
     * Fetches the full attachment bytes from a Gmail message, authenticating
     * as a SPECIFIC account (that RFI's own sender's RFI Settings).
     * Used by RfiGmailSyncService to download attachments from incoming replies.
     * 
     * @param messageId Gmail message ID
     * @param attachmentId Gmail attachment ID
     * @param refreshTokenToUse the sender's own OAuth refresh token
     * @param clientIdToUse the sender's own OAuth Client ID
     * @param clientSecretToUse the sender's own OAuth Client Secret
     * @return The raw attachment bytes
     */
    public byte[] fetchAttachmentBytes(
            String messageId, String attachmentId,
            String refreshTokenToUse, String clientIdToUse, String clientSecretToUse) throws Exception {
        Gmail gmail = buildGmailService(
                (refreshTokenToUse != null && !refreshTokenToUse.isBlank()) ? refreshTokenToUse : refreshToken,
                (clientIdToUse != null && !clientIdToUse.isBlank()) ? clientIdToUse : clientId,
                (clientSecretToUse != null && !clientSecretToUse.isBlank()) ? clientSecretToUse : clientSecret);

        try {
            MessagePartBody attachment = 
                    gmail.users().messages().attachments()
                            .get("me", messageId, attachmentId)
                            .execute();

            String data = attachment.getData();
            if (data != null) {
                return Base64.getUrlDecoder().decode(data);
            }
            return new byte[0];
        } catch (Exception e) {
            log.error("Error fetching attachment {} from message {}: {}", 
                    attachmentId, messageId, e.getMessage());
            throw e;
        }
    }

    /**
     * Gets the legacy single globally-configured "from" email address.
     * RfiGmailSyncService no longer calls this — it now uses that RFI's own
     * sender's Gmail address (from RfiSettings) to determine email
     * direction (outgoing vs incoming) instead of this single global value.
     * Left in place only for any other not-yet-updated caller.
     */
    public String getConfiguredFromEmail() {
        return fromEmail;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Builds and authenticates a Gmail service instance using the single
     * legacy globally-configured GMAIL_REFRESH_TOKEN. Only used by the
     * no-arg fallback overloads above (fetchThreadMessages(threadId),
     * searchThreadIdsBySubject(subject), fetchAttachmentBytes(id, attId))
     * for any not-yet-updated caller — RfiGmailSyncService now always
     * calls the per-user overloads with that RFI's own sender's RFI
     * Settings credentials instead.
     */
    private Gmail buildGmailService() throws Exception {
        return buildGmailService(refreshToken, clientId, clientSecret);
    }

    /**
     * Builds and authenticates a Gmail service instance for a SPECIFIC
     * refresh token + Client ID/Secret — this is what lets
     * sendWithAttachment/sendReplyInThread send as whichever account's
     * token AND own OAuth app credentials they were given, instead of
     * always the one globally-configured application.properties client.
     */
    private Gmail buildGmailService(String refreshTokenToUse, String clientIdToUse, String clientSecretToUse) throws Exception {
        if (refreshTokenToUse == null || refreshTokenToUse.isBlank()) {
            throw new Exception("No Gmail refresh token available to authenticate with");
        }
        if (clientIdToUse == null || clientIdToUse.isBlank()
                || clientSecretToUse == null || clientSecretToUse.isBlank()) {
            throw new Exception("Gmail API OAuth client is not configured — add your Client ID "
                    + "and Client Secret in RFI Settings");
        }
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                .setJsonFactory(GsonFactory.getDefaultInstance())
                .setClientSecrets(clientIdToUse, clientSecretToUse)
                .build()
                .setRefreshToken(refreshTokenToUse);
        credential.refreshToken();
        credential = credential.createScoped(Collections.singleton(GmailScopes.GMAIL_MODIFY));  // ✅ FIXED

        return new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                credential)  // ← Now uses properly scoped credential
                .setApplicationName("RFI Email Service")
                .build();
    }

    /**
     * Parses a Gmail API Message object into our GmailFetchedMessage DTO.
     */
    private GmailFetchedMessage parseGmailMessage(Message msg) {
        GmailFetchedMessage result = new GmailFetchedMessage();
        result.setGmailMessageId(msg.getId());
        result.setGmailThreadId(msg.getThreadId());

        MessagePart payload = msg.getPayload();
        if (payload != null) {
            List<MessagePartHeader> headers = payload.getHeaders();
            if (headers != null) {
                for (MessagePartHeader header : headers) {
                    String name = header.getName();
                    String value = header.getValue();
                    if ("From".equalsIgnoreCase(name)) {
                        result.setFromAddress(value);
                    } else if ("To".equalsIgnoreCase(name)) {
                        result.setToAddress(value);
                    } else if ("Subject".equalsIgnoreCase(name)) {
                        result.setSubject(value);
                    } else if ("In-Reply-To".equalsIgnoreCase(name)) {
                        result.setInReplyTo(value);
                    } else if ("References".equalsIgnoreCase(name)) {
                        result.setReferences(value);
                    }
                }
            }

            // Extract body
            String bodyText = extractMessageBody(payload);
            result.setBodyText(bodyText);

            // Extract snippet
            String snippet = msg.getSnippet();
            result.setSnippet(snippet != null ? snippet : (bodyText != null ? bodyText.substring(0, Math.min(200, bodyText.length())) : ""));

            // Extract attachments
            result.setAttachments(extractAttachments(payload));
        }

        // Parse the message date from the internalDate (milliseconds since epoch)
        if (msg.getInternalDate() != null) {
            LocalDateTime messageDate = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(msg.getInternalDate()),
                    ZoneId.systemDefault());
            result.setMessageDate(messageDate);
        }

        return result;
    }

    /**
     * Extracts the text body from a Gmail message (handles both plain text and HTML).
     */
    private String extractMessageBody(MessagePart part) {
        if (part == null) {
            return "";
        }

        String mimeType = part.getMimeType();
        String body = "";

        if ("text/plain".equals(mimeType)) {
            MessagePartBody partBody = part.getBody();
            if (partBody != null && partBody.getData() != null) {
                body = new String(Base64.getUrlDecoder().decode(partBody.getData()));
            }
        } else if ("text/html".equals(mimeType)) {
            // Prefer HTML if plain text isn't available
            MessagePartBody partBody = part.getBody();
            if (partBody != null && partBody.getData() != null) {
                body = new String(Base64.getUrlDecoder().decode(partBody.getData()));
            }
        }

        // Check multipart
        if (part.getParts() != null) {
            for (MessagePart subPart : part.getParts()) {
                String subBody = extractMessageBody(subPart);
                if (!subBody.isEmpty()) {
                    body = subBody;
                    break;
                }
            }
        }

        return body;
    }

    private List<GmailFetchedAttachment> extractAttachments(MessagePart part) {
    List<GmailFetchedAttachment> attachments = new ArrayList<>();

    if (part == null || part.getParts() == null) {
        return attachments;
    }

    for (MessagePart subPart : part.getParts()) {
        // The Gmail API has no explicit "disposition" field on MessagePart —
        // a part is treated as an attachment when it carries a non-empty
        // filename (the same signal Gmail's own UI/clients use).
        String filename = subPart.getFilename();
        if (filename != null && !filename.isEmpty()) {
            GmailFetchedAttachment att = new GmailFetchedAttachment();
            att.setGmailAttachmentId(subPart.getBody().getAttachmentId());
            att.setFilename(subPart.getFilename());
            att.setMimeType(subPart.getMimeType());
            att.setSize(subPart.getBody().getSize() != null ? subPart.getBody().getSize().longValue() : 0L);
            attachments.add(att);
        }

        // Recurse — attachments can be nested inside nested multipart parts.
        if (subPart.getParts() != null && !subPart.getParts().isEmpty()) {
            attachments.addAll(extractAttachments(subPart));
        }
    }

    return attachments;
}

    /**
     * Escapes special characters in a Gmail search query.
     */
    private String escapeGmailSearchQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.replace("\"", "\\\"").replace("\\", "\\\\");
    }
}