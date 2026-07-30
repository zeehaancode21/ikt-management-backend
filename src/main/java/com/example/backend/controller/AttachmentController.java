// AttachmentController.java
package com.example.backend.controller;

import com.example.backend.entity.Attachment;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/attachments")
// @CrossOrigin(
//     origins = "http://localhost:5173",
//     allowCredentials = "true",
//     allowedHeaders = "*"
// )
public class AttachmentController {

    @Autowired
    private FileStorageService fileStorageService;

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
     * Resolve a MediaType for the file: prefer the MIME type captured at
     * upload time, fall back to probing the file on disk, and only default
     * to a generic binary type if both fail.
     */
    private MediaType resolveMediaType(Attachment attachment, Path filePath) {
        String stored = attachment.getFileType();
        if (stored != null && !stored.isBlank()) {
            try {
                return MediaType.parseMediaType(stored);
            } catch (Exception ignored) {
                // fall through to probing
            }
        }
        try {
            String probed = Files.probeContentType(filePath);
            if (probed != null) {
                return MediaType.parseMediaType(probed);
            }
        } catch (IOException ignored) {
            // fall through to default
        }
        return MediaType.APPLICATION_OCTET_STREAM;
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
     * This is the explicit, user-initiated download action (the 📥 button).
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        try {
            Attachment attachment = fileStorageService.getAttachment(id);
            requireAttachmentAccess(attachment);
            Path filePath = fileStorageService.getFilePath(id);

            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + attachment.getOriginalName() + "\"")
                .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Default "view" action for a file: served with its real content type
     * and an `inline` disposition so the browser renders it (image, video,
     * PDF, text, etc.) instead of downloading it whenever it's able to.
     * For types the browser can't render natively (e.g. .docx/.xlsx) it
     * will fall back to its own open/download behavior — that's a browser
     * limitation, not something a server header can override.
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> previewFile(@PathVariable Long id) {
        try {
            Attachment attachment = fileStorageService.getAttachment(id);
            requireAttachmentAccess(attachment);
            Path filePath = fileStorageService.getFilePath(id);
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            MediaType mediaType = resolveMediaType(attachment, filePath);

            return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"" + attachment.getOriginalName() + "\"")
                .body(resource);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}