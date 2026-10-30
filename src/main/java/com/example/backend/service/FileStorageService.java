// FileStorageService.java
package com.example.backend.service;

import com.example.backend.entity.Attachment;
import com.example.backend.entity.AttachmentData;
import com.example.backend.repository.AttachmentDataRepository;
import com.example.backend.repository.AttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates and reads Attachment records. There are two ways a file can be kept:
 *
 * 1. IN THE DATABASE (table attachment_data, LONGBLOB) - storeFiles / storeBytes.
 *    Used for everything that has no Gmail copy (chat uploads, etc.).
 *
 * 2. IN GMAIL - storeGmailReference / storePlaceholders + linkToGmail.
 *    Used for files on an RFI email thread. Only a small "pointer" row is saved (which Gmail
 *    message / file, in whose mailbox); the bytes are never copied into our database. They are
 *    streamed from Gmail when someone clicks View or Download (see AttachmentController and
 *    GmailAttachmentService).
 *
 * Attachments saved on the server's disk by very old versions are still readable while that file
 * exists (see getFileBytes).
 */
@Service
public class FileStorageService {

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private AttachmentDataRepository attachmentDataRepository;

    // Strips anything that isn't a short, plain extension so a stored name
    // can never contain odd characters.
    private String sanitizeExtension(String originalFilename) {
        String fileExtension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        fileExtension = fileExtension.replaceAll("[^a-zA-Z0-9.]", "");
        if (fileExtension.length() > 10) {
            fileExtension = fileExtension.substring(0, 10);
        }
        return fileExtension;
    }

    // ── 1. Stored in the database ────────────────────────────────────────────────────────────

    public List<Attachment> storeFiles(List<MultipartFile> files, String uploadedBy) throws IOException {
        List<Attachment> attachments = new ArrayList<>();

        for (MultipartFile file : files) {
            attachments.add(saveToDatabase(
                    file.getBytes(), file.getOriginalFilename(), file.getContentType(), uploadedBy));
        }

        return attachments;
    }

    /** Same as storeFiles but for raw bytes rather than a MultipartFile. */
    public Attachment storeBytes(byte[] data, String originalFilename, String contentType, String uploadedBy) throws IOException {
        return saveToDatabase(data, originalFilename, contentType, uploadedBy);
    }

    private Attachment saveToDatabase(byte[] data, String originalFilename, String contentType, String uploadedBy) throws IOException {
        if (data == null) {
            throw new IOException("No file data to store");
        }

        Attachment saved = attachmentRepository.save(
                newRecord(originalFilename, contentType, data.length, uploadedBy));

        try {
            attachmentDataRepository.save(new AttachmentData(saved, data));
        } catch (RuntimeException e) {
            // Don't leave an attachment record that has no file behind it.
            try {
                attachmentRepository.delete(saved);
            } catch (RuntimeException ignored) {
                // best-effort clean-up; the original error is what matters
            }
            throw e;
        }

        return saved;
    }

    // ── 2. Kept in Gmail (no bytes stored) ───────────────────────────────────────────────────

    /**
     * Saves a pointer to a file that already sits in a Gmail message (used when a reply is
     * imported by "Sync Gmail"). Nothing is downloaded and nothing is written to attachment_data.
     */
    public Attachment storeGmailReference(String originalFilename, String contentType, Long size, String uploadedBy,
                                          String gmailMessageId, String gmailAttachmentId, String gmailPartId,
                                          String gmailAccount) {
        Attachment attachment = newRecord(originalFilename, contentType, size != null ? size : 0L, uploadedBy);
        applyGmailPointer(attachment, gmailMessageId, gmailAttachmentId, gmailPartId, gmailAccount);
        return attachmentRepository.save(attachment);
    }

    /**
     * Creates records for files the app is ABOUT to email, WITHOUT saving their bytes. Right after
     * the email is sent the caller must call linkToGmail (the file is then read from the sent
     * message in Gmail) or, if that is not possible, storeBytesFor (safe fallback, file kept in
     * the database). If the send fails, just delete the records.
     */
    public List<Attachment> storePlaceholders(List<MultipartFile> files, String uploadedBy) {
        List<Attachment> result = new ArrayList<>();
        for (MultipartFile file : files) {
            result.add(storePlaceholder(file, uploadedBy));
        }
        return result;
    }

    public Attachment storePlaceholder(MultipartFile file, String uploadedBy) {
        return attachmentRepository.save(
                newRecord(file.getOriginalFilename(), file.getContentType(), file.getSize(), uploadedBy));
    }

    /** Points an attachment at its file inside a Gmail message. */
    public Attachment linkToGmail(Attachment attachment, String gmailMessageId, String gmailAttachmentId,
                                  String gmailPartId, String gmailAccount) {
        applyGmailPointer(attachment, gmailMessageId, gmailAttachmentId, gmailPartId, gmailAccount);
        return attachmentRepository.save(attachment);
    }

    /** Fallback for a placeholder that could not be linked to Gmail: keep the bytes in the database. */
    public void storeBytesFor(Attachment attachment, byte[] data) {
        attachmentDataRepository.save(new AttachmentData(attachment, data));
    }

    /** Drops the stored bytes of an attachment (after it was linked to its Gmail copy). */
    public void deleteStoredBytes(Long attachmentId) {
        attachmentDataRepository.deleteByAttachmentId(attachmentId);
    }

    private static void applyGmailPointer(Attachment a, String messageId, String attachmentId,
                                          String partId, String account) {
        a.setGmailMessageId(messageId);
        a.setGmailAttachmentId(attachmentId);
        a.setGmailPartId(partId);
        a.setGmailAccount(account);
        a.setFilePath(Attachment.GMAIL_PATH_PREFIX + messageId + "/" + (partId != null ? partId : "0"));
    }

    private Attachment newRecord(String originalFilename, String contentType, long size, String uploadedBy) {
        String storedFilename = UUID.randomUUID().toString() + sanitizeExtension(originalFilename);

        Attachment attachment = new Attachment();
        attachment.setFilename(storedFilename);
        attachment.setOriginalName(originalFilename != null && !originalFilename.isBlank() ? originalFilename : storedFilename);
        attachment.setFileType(contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream");
        attachment.setFileSize(size);
        attachment.setFilePath(Attachment.DB_PATH_PREFIX + storedFilename);
        attachment.setUploadedBy(uploadedBy != null ? uploadedBy : "unknown");
        return attachment;
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the bytes of a file that is stored by us, or null if there are none.
     * (Gmail-backed attachments have no stored bytes - AttachmentController streams those from Gmail.)
     * Looks in the database first; for older attachments that were saved to disk, falls back to the
     * disk file if it still exists.
     */
    public byte[] getFileBytes(Long attachmentId) throws IOException {
        Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null || attachment.isGmailBacked()) {
            return null;
        }

        AttachmentData stored = attachmentDataRepository.findByAttachment_Id(attachmentId).orElse(null);
        if (stored != null) {
            return stored.getData();
        }

        // Older attachment saved on disk before files moved into the database.
        String oldPath = attachment.getFilePath();
        if (oldPath != null && !oldPath.startsWith(Attachment.DB_PATH_PREFIX)
                && !oldPath.startsWith(Attachment.GMAIL_PATH_PREFIX)) {
            Path legacyFile = Paths.get(oldPath);
            if (Files.isRegularFile(legacyFile)) {
                return Files.readAllBytes(legacyFile);
            }
        }

        return null;
    }

    public Attachment getAttachment(Long id) {
        return attachmentRepository.findById(id).orElse(null);
    }
}