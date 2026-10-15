package com.example.backend.controller;

import com.example.backend.dto.DocumentMetaView;
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
        List<DocumentMetaView> docs = repo.findAllMeta(); // metadata only: no file bytes loaded
        return ResponseEntity.ok(docs.stream().map(this::toMeta).collect(Collectors.toList()));
    }

    @GetMapping("/project/{projectName}")
    public ResponseEntity<List<Map<String, Object>>> listByProject(@PathVariable String projectName) {
        List<DocumentMetaView> docs = repo.findMetaByProjectName(projectName); // no file bytes loaded
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
        // Polled while an upload is processing: metadata only, never the file bytes.
        return repo.findMetaById(id).map(doc -> {
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
        repo.deleteDocumentById(id); // single DELETE; deleteById() would load the file bytes first
        return ResponseEntity.ok(Map.of("message", "Document deleted", "id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        // Old version loaded the whole file into memory and wrote it back to the DB just to
        // change a description. This only touches the metadata columns.
        return repo.findMetaById(id).map(current -> {
            String description = body.containsKey("description") ? body.get("description") : current.getDescription();
            String category = body.containsKey("category") ? body.get("category").toUpperCase() : current.getCategory();
            String projectName = body.containsKey("projectName") ? body.get("projectName") : current.getProjectName();
            repo.updateMeta(id, description, category, projectName);
            return ResponseEntity.ok(toMeta(repo.findMetaById(id).orElse(current)));
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMeta(Document doc) {
        return meta(doc.getId(), doc.getFileName(), doc.getOriginalFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getDescription(), doc.getCategory(), doc.getProjectName(),
                doc.getUploadedBy(), doc.getUploadedAt());
    }

    private Map<String, Object> toMeta(DocumentMetaView doc) {
        return meta(doc.getId(), doc.getFileName(), doc.getOriginalFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getDescription(), doc.getCategory(), doc.getProjectName(),
                doc.getUploadedBy(), doc.getUploadedAt());
    }

    private Map<String, Object> meta(Long id, String fileName, String originalFileName, String fileType,
                                     Long fileSize, String description, String category, String projectName,
                                     String uploadedBy, java.time.LocalDateTime uploadedAt) {
        return Map.of(
                "id", id,
                "fileName", fileName != null ? fileName : "",
                "originalFileName", originalFileName != null ? originalFileName : "",
                "fileType", fileType != null ? fileType : "",
                "fileSize", fileSize != null ? fileSize : 0L,
                "description", description != null ? description : "",
                "category", category != null ? category : "GENERAL",
                "projectName", projectName != null ? projectName : "",
                "uploadedBy", uploadedBy != null ? uploadedBy : "",
                "uploadedAt", uploadedAt != null ? uploadedAt.toString() : ""
        );
    }

    private java.util.HashMap<String, Object> toMutableMeta(Document doc) {
        return new java.util.HashMap<>(toMeta(doc));
    }

    private java.util.HashMap<String, Object> toMutableMeta(DocumentMetaView doc) {
        return new java.util.HashMap<>(toMeta(doc));
    }
}