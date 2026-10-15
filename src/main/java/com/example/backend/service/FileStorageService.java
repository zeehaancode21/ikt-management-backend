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
 * Stores uploaded attachments INSIDE THE DATABASE (table attachment_data)
 * instead of on the server's local disk.
 *
 * Render's free plan deletes local files on every redeploy, restart and
 * spin-down, which left the database pointing at files that no longer
 * existed. The database keeps the files safe through all of that.
 *
 * Attachments uploaded before this change were saved on disk. They can still
 * be read while that file happens to exist (see getFileBytes), but files
 * already wiped from Render's disk cannot be recovered.
 */
@Service
public class FileStorageService {

    /** Marker saved in Attachment.filePath for files stored in the database. */
    private static final String DB_PATH_PREFIX = "db:";

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

    public List<Attachment> storeFiles(List<MultipartFile> files, String uploadedBy) throws IOException {
        List<Attachment> attachments = new ArrayList<>();

        for (MultipartFile file : files) {
            attachments.add(saveToDatabase(
                    file.getBytes(), file.getOriginalFilename(), file.getContentType(), uploadedBy));
        }

        return attachments;
    }

    /**
     * Same storage pipeline as storeFiles, but for raw bytes rather than a
     * MultipartFile — used by RfiGmailSyncService to persist attachments
     * downloaded from a Gmail reply, where there's no MultipartFile/HTTP
     * upload involved, just bytes fetched from the Gmail API.
     */
    public Attachment storeBytes(byte[] data, String originalFilename, String contentType, String uploadedBy) throws IOException {
        return saveToDatabase(data, originalFilename, contentType, uploadedBy);
    }

    private Attachment saveToDatabase(byte[] data, String originalFilename, String contentType, String uploadedBy) throws IOException {
        if (data == null) {
            throw new IOException("No file data to store");
        }

        String storedFilename = UUID.randomUUID().toString() + sanitizeExtension(originalFilename);

        Attachment attachment = new Attachment();
        attachment.setFilename(storedFilename);
        attachment.setOriginalName(originalFilename != null ? originalFilename : storedFilename);
        attachment.setFileType(contentType != null ? contentType : "application/octet-stream");
        attachment.setFileSize((long) data.length);
        attachment.setFilePath(DB_PATH_PREFIX + storedFilename);
        attachment.setUploadedBy(uploadedBy);

        Attachment saved = attachmentRepository.save(attachment);

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

    /**
     * Returns the file's bytes, or null if the file can't be found.
     * Looks in the database first; for older attachments that were saved to
     * disk, falls back to the disk file if it still exists.
     */
    public byte[] getFileBytes(Long attachmentId) throws IOException {
        Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null) {
            return null;
        }

        AttachmentData stored = attachmentDataRepository.findByAttachment_Id(attachmentId).orElse(null);
        if (stored != null) {
            return stored.getData();
        }

        // Older attachment saved on disk before this change.
        String oldPath = attachment.getFilePath();
        if (oldPath != null && !oldPath.startsWith(DB_PATH_PREFIX)) {
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