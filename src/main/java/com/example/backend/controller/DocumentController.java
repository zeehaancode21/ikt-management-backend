package com.example.backend.controller;

import com.example.backend.entity.Document;
import com.example.backend.repository.DocumentRepository;
import com.example.backend.service.DocumentUploadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentRepository repo;
    private final DocumentUploadService uploadService;

    public DocumentController(DocumentRepository repo, DocumentUploadService uploadService) {
        this.repo = repo;
        this.uploadService = uploadService;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<Document> docs = repo.findAllByOrderByUploadedAtDesc();
        return ResponseEntity.ok(docs.stream().map(this::toMeta).collect(Collectors.toList()));
    }

    @GetMapping("/project/{projectName}")
    public ResponseEntity<List<Map<String, Object>>> listByProject(@PathVariable String projectName) {
        List<Document> docs = repo.findByProjectNameOrderByUploadedAtDesc(projectName);
        return ResponseEntity.ok(docs.stream().map(this::toMeta).collect(Collectors.toList()));
    }

    /**
     * Upload — responds the moment the last byte of the request lands.
     *
     * 1. Validate (fast, no I/O)
     * 2. Stream multipart body → temp file on disk  (unavoidable network wait,
     *    but zero heap pressure and zero DB involvement on this thread)
     * 3. Save metadata-only row (no LONGBLOB) — fast
     * 4. Return 202 immediately
     * 5. Background thread reads temp file → writes LONGBLOB → deletes temp
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "category", defaultValue = "GENERAL") String category,
            @RequestParam(value = "projectName", required = false) String projectName
    ) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        if (file.getSize() > 20 * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "File too large (max 20MB)"));
        }

        // Stream to temp file — uses the servlet container's already-buffered
        // input stream. No second heap copy; no DB connection held open.
        Path tempFile = Files.createTempFile("upload-", "-" + file.getOriginalFilename());
        file.transferTo(tempFile);

        // Metadata-only save (no blob) — tiny, fast DB write
        Document doc = new Document();
        doc.setOriginalFileName(file.getOriginalFilename());
        doc.setFileName(file.getOriginalFilename());
        doc.setFileType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        doc.setFileSize(file.getSize());
        doc.setDescription(description);
        doc.setCategory(category.toUpperCase());
        doc.setProjectName(projectName);
        doc.setUploadedBy(currentUsername());
        // fileData left null — background task fills it in

        Document saved = repo.save(doc);

        // Hand temp file path to background thread and return immediately
        uploadService.persistFromTempFile(saved.getId(), tempFile);

        Map<String, Object> response = toMutableMeta(saved);
        response.put("status", "PROCESSING");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

   @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long id) {
        return repo.findById(id).map(doc -> {
            Map<String, Object> meta = toMutableMeta(doc);
            meta.put("status", doc.getUploadStatus() != null ? doc.getUploadStatus() : "PROCESSING");
            return ResponseEntity.ok(meta);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return repo.findById(id).map(doc -> {
            if (doc.getFileData() == null || doc.getFileData().length == 0) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build();
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(doc.getFileType()));
            headers.setContentDispositionFormData("attachment", doc.getOriginalFileName());
            headers.setContentLength(doc.getFileData().length);
            return ResponseEntity.ok().headers(headers).body(doc.getFileData());
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Document deleted", "id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return repo.findById(id).map(doc -> {
            if (body.containsKey("description")) doc.setDescription(body.get("description"));
            if (body.containsKey("category")) doc.setCategory(body.get("category").toUpperCase());
            if (body.containsKey("projectName")) doc.setProjectName(body.get("projectName"));
            repo.save(doc);
            return ResponseEntity.ok(toMeta(doc));
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMeta(Document doc) {
        return Map.of(
                "id", doc.getId(),
                "fileName", doc.getFileName() != null ? doc.getFileName() : "",
                "originalFileName", doc.getOriginalFileName() != null ? doc.getOriginalFileName() : "",
                "fileType", doc.getFileType() != null ? doc.getFileType() : "",
                "fileSize", doc.getFileSize() != null ? doc.getFileSize() : 0L,
                "description", doc.getDescription() != null ? doc.getDescription() : "",
                "category", doc.getCategory() != null ? doc.getCategory() : "GENERAL",
                "projectName", doc.getProjectName() != null ? doc.getProjectName() : "",
                "uploadedBy", doc.getUploadedBy() != null ? doc.getUploadedBy() : "",
                "uploadedAt", doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : ""
        );
    }

    private java.util.HashMap<String, Object> toMutableMeta(Document doc) {
        return new java.util.HashMap<>(toMeta(doc));
    }
}