// AttachmentController.java
package com.example.backend.controller;

import com.example.backend.entity.Attachment;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.FileStorageService;
import com.example.backend.service.GmailAttachmentService;
import com.example.backend.service.GmailEmailService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/attachments")
public class AttachmentController {

    /**
     * Types a browser may show inside its own tab. Anything else requested through /preview is sent
     * as a plain download instead. This matters because files that arrive by email come from
     * outside people: an .html or .svg shown inline from a blob: URL would run its scripts with
     * the app's own origin (and its login token).
     */
    private static final Set<String> INLINE_SAFE_TYPES = Set.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "text/plain",
            "video/mp4", "video/webm",
            "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/webm");

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private GmailAttachmentService gmailAttachmentService;

    @Autowired
    private UserRepository userRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private void requireAttachmentAccess(Attachment attachment) {
        if (attachment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
        }
        String username = currentUsername();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to this file");
        }
    }

    /**
     * ETag for an attachment. An attachment's bytes never change for a given id, so id + size is
     * enough. The browser re-asks "has it changed?" with If-None-Match and gets a tiny 304 instead
     * of the whole file again (saves network, a database read and, for Gmail files, a Gmail call).
     * Access is still checked on every request before the 304 is sent, so a revoked user never
     * gets a cached copy served.
     */
    private static String attachmentEtag(Attachment a) {
        return "\"att-" + a.getId() + "-" + a.getFileSize() + "\"";
    }

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        return ifNoneMatch != null && ifNoneMatch.contains(etag);
    }

    /**
     * Resolve a MediaType for the file: prefer the MIME type captured at upload time (or reported
     * by Gmail), fall back to guessing from the file name, and only default to a generic binary
     * type if both fail. A generic "application/octet-stream" is also re-guessed from the name,
     * because email clients often label PDFs and images that way.
     */
    private MediaType resolveMediaType(Attachment attachment) {
        String stored = attachment.getFileType();
        if (stored != null && !stored.isBlank()) {
            try {
                MediaType parsed = MediaType.parseMediaType(stored);
                if (!MediaType.APPLICATION_OCTET_STREAM.equalsTypeAndSubtype(parsed)) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // fall through to guessing from the name
            }
        }
        String name = attachment.getOriginalName();
        if (name != null) {
            return MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM);
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static boolean isInlineSafe(MediaType type) {
        return INLINE_SAFE_TYPES.contains(type.getType() + "/" + type.getSubtype());
    }

    @PostMapping("/upload")
    public ResponseEntity<List<Attachment>> uploadFiles(@RequestParam("files") List<MultipartFile> files) {
        try {
            List<Attachment> attachments = fileStorageService.storeFiles(files, currentUsername());
            return ResponseEntity.ok(attachments);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Always forces a "Save As" download regardless of file type.
     * This is the explicit, user-initiated download action (the download button).
     */
    @GetMapping("/{id}/download")
    public void downloadFile(@PathVariable Long id,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse response) throws IOException {
        serve(id, ifNoneMatch, response, true);
    }

    /**
     * Default "view" action for a file: served with its real content type and an `inline`
     * disposition so the browser renders it (image, video, PDF, text) instead of downloading it.
     * Types the browser can't render safely (e.g. .docx/.xlsx, .html, .svg) are sent as a download.
     */
    @GetMapping("/{id}/preview")
    public void previewFile(@PathVariable Long id,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse response) throws IOException {
        serve(id, ifNoneMatch, response, false);
    }

    /**
     * Sends one file to the browser.
     *  - Gmail-backed file: opened in the Gmail thread and streamed straight through (a few KB of
     *    memory, no database connection held while the bytes travel).
     *  - Stored file (chat uploads, older attachments): read from attachment_data.
     * Every failure is raised BEFORE the first byte is written, so it becomes a proper HTTP error.
     */
    private void serve(Long id, String ifNoneMatch, HttpServletResponse response, boolean forceDownload) throws IOException {
        Attachment attachment = fileStorageService.getAttachment(id);
        requireAttachmentAccess(attachment);

        String etag = attachmentEtag(attachment);
        if (etagMatches(ifNoneMatch, etag)) {
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            response.setHeader(HttpHeaders.ETAG, etag);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");
            return;
        }

        MediaType type = resolveMediaType(attachment);
        boolean inline = !forceDownload && isInlineSafe(type);
        MediaType sendType = inline ? type : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(attachment.getOriginalName(), StandardCharsets.UTF_8)
                .build();

        if (attachment.isGmailBacked()) {
            try (GmailEmailService.AttachmentContent content = gmailAttachmentService.open(attachment)) {
                applyHeaders(response, sendType, disposition, etag);
                content.writeTo(response.getOutputStream());
            }
            return;
        }

        byte[] data = fileStorageService.getFileBytes(id);
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        applyHeaders(response, sendType, disposition, etag);
        response.setContentLengthLong(data.length);
        response.getOutputStream().write(data);
    }

    private static void applyHeaders(HttpServletResponse response, MediaType type,
                                     ContentDisposition disposition, String etag) {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(type.toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}