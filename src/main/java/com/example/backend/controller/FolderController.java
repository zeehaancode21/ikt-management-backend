package com.example.backend.controller;

import com.example.backend.entity.Document;
import com.example.backend.entity.Folder;
import com.example.backend.repository.DocumentRepository;
import com.example.backend.repository.FolderRepository;
import com.example.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/folders")
@RequiredArgsConstructor
// @CrossOrigin(
//     origins = {"http://localhost:5173", "http://localhost:3000"},
//     allowCredentials = "true",
//     allowedHeaders = "*",
//     methods = {
//         RequestMethod.GET, RequestMethod.POST,
//         RequestMethod.PUT, RequestMethod.DELETE,
//         RequestMethod.OPTIONS
//     }
// )
public class FolderController {

    private final FolderRepository folderRepo;
    private final DocumentRepository documentRepo;
    private final UserRepository userRepo;

    // ── Security helpers ─────────────────────────────────────────────────────
    private String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private boolean isManagerOrOwner(String username) {
        return userRepo.findByUsername(username)
                .map(u -> {
                    String role = u.getRole();
                    return role != null &&
                            List.of("OWNER", "ADMIN", "MANAGER").contains(role.trim().toUpperCase());
                })
                .orElse(false);
    }

    /** Throws 403 unless the caller IS targetUsername or is a manager/owner. */
    private void requireOwnerOrSelf(String targetUsername) {
        String actor = currentUser();
        if (!actor.equals(targetUsername) && !isManagerOrOwner(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to access this user's folders");
        }
    }

    /** Throws 403 unless the caller created this folder or is a manager/owner. */
    private void requireFolderOwnerOrSelf(Folder folder) {
        requireOwnerOrSelf(folder.getCreatedBy());
    }

    // ── Strip blob bytes before serialising ───────────────────────────────────
    private void stripBlobs(Folder folder) {
        if (folder == null) return;
        if (folder.getDocuments() != null) {
            folder.getDocuments().forEach(d -> d.setFileData(null));
        }
        if (folder.getSubFolders() != null) {
            folder.getSubFolders().forEach(this::stripBlobs);
        }
    }

    // =========================================================================
    // FOLDER CRUD
    // =========================================================================

    @GetMapping("/{username}")
    public ResponseEntity<List<Folder>> getUserFolders(@PathVariable String username) {
        requireOwnerOrSelf(username);
        try {
            List<Folder> folders = folderRepo
                    .findAllByCreatedByAndParentIsNullOrderByCreatedAtDesc(username);
            folders.forEach(this::stripBlobs);
            return ResponseEntity.ok(folders);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{username}/{folderId}")
    public ResponseEntity<?> getFolder(
            @PathVariable String username,
            @PathVariable Long folderId) {
        requireOwnerOrSelf(username);
        return folderRepo.findById(folderId).map(folder -> {
            requireFolderOwnerOrSelf(folder);
            stripBlobs(folder);
            return ResponseEntity.ok(folder);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createFolder(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String desc = body.getOrDefault("description", "") != null
                ? (String) body.getOrDefault("description", "") : "";

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Folder name is required."));
        }

        Long parentId = null;
        if (body.get("parentId") != null) {
            try {
                parentId = Long.valueOf(body.get("parentId").toString());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid parentId."));
            }
        }

        final String user = currentUser();

        boolean duplicate = (parentId == null)
                ? folderRepo.existsByNameAndCreatedByAndParentIsNull(name.trim(), user)
                : folderRepo.existsByNameAndCreatedByAndParentId(name.trim(), user, parentId);

        if (duplicate) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A folder with this name already exists here."));
        }

        Folder parent = null;
        if (parentId != null) {
            var opt = folderRepo.findById(parentId);
            if (opt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Parent folder not found."));
            }
            parent = opt.get();
            requireFolderOwnerOrSelf(parent);

            if (parent.isLeaf()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "Cannot create a sub-folder inside a folder that already contains files. "
                    + "Upload files OR create sub-folders — not both."
                ));
            }
        }

        Folder folder = new Folder();
        folder.setName(name.trim());
        folder.setDescription(desc.trim());
        folder.setCreatedBy(user);
        folder.setParent(parent);

        Folder saved = folderRepo.save(folder);
        stripBlobs(saved);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateFolder(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return folderRepo.findById(id).map(folder -> {
            requireFolderOwnerOrSelf(folder);
            if (body.containsKey("name") && !body.get("name").isBlank()) {
                folder.setName(body.get("name").trim());
            }
            if (body.containsKey("description")) {
                folder.setDescription(body.get("description"));
            }
            Folder saved = folderRepo.save(folder);
            stripBlobs(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFolder(@PathVariable Long id) {
        var opt = folderRepo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        requireFolderOwnerOrSelf(opt.get());
        folderRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Folder deleted."));
    }

    // =========================================================================
    // DOCUMENT CRUD
    // =========================================================================

    @GetMapping("/{username}/{folderId}/documents")
    public ResponseEntity<?> getDocuments(
            @PathVariable String username,
            @PathVariable Long folderId) {
        requireOwnerOrSelf(username);
        var folderOpt = folderRepo.findById(folderId);
        if (folderOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        requireFolderOwnerOrSelf(folderOpt.get());
        List<Document> docs = documentRepo.findByFolder_IdOrderByUploadedAtDesc(folderId);
        docs.forEach(d -> d.setFileData(null));
        return ResponseEntity.ok(docs);
    }

    @PostMapping("/{username}/{folderId}/documents")
    public ResponseEntity<?> uploadDocument(
            @PathVariable String username,
            @PathVariable Long folderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description) {
        requireOwnerOrSelf(username);

        return folderRepo.findById(folderId).map(folder -> {
            requireFolderOwnerOrSelf(folder);
            try {
                if (file.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "File is empty."));
                }
                if (file.getSize() > 20L * 1024 * 1024) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "File exceeds 20 MB limit."));
                }

                if (folder.isBranch()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error",
                        "Cannot upload files into a folder that contains sub-folders. "
                        + "Upload files into a leaf folder instead."
                    ));
                }

                Document doc = new Document();
                doc.setFolder(folder);
                doc.setFileName(UUID.randomUUID() + "_" + file.getOriginalFilename());
                doc.setOriginalFileName(file.getOriginalFilename());
                doc.setFileType(file.getContentType() != null
                        ? file.getContentType() : "application/octet-stream");
                doc.setFileSize(file.getSize());
                doc.setDescription(description);
                doc.setUploadedBy(username);
                doc.setFileData(file.getBytes());

                Document saved = documentRepo.save(doc);
                saved.setFileData(null);
                return ResponseEntity.ok(saved);

            } catch (IOException e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Failed to read file: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @Transactional(readOnly = true)
    @GetMapping("/{username}/{folderId}/documents/{docId}/download")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable String username,
            @PathVariable Long folderId,
            @PathVariable Long docId) {
        return documentRepo.findById(docId).map(doc -> {
            if (doc.getFolder() == null || !doc.getFolder().getId().equals(folderId)) {
                return ResponseEntity.notFound().<byte[]>build();
            }
            if (doc.getFileData() == null || doc.getFileData().length == 0) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).<byte[]>build();
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + doc.getOriginalFileName() + "\"")
                    .contentType(MediaType.parseMediaType(
                            doc.getFileType() != null
                                    ? doc.getFileType() : "application/octet-stream"))
                    .body(doc.getFileData());
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{username}/{folderId}/documents/{docId}/status")
    public ResponseEntity<?> documentStatus(
            @PathVariable String username,
            @PathVariable Long folderId,
            @PathVariable Long docId) {
        requireOwnerOrSelf(username);
        return documentRepo.findById(docId).map(doc -> {
            if (doc.getFolder() == null || !doc.getFolder().getId().equals(folderId)) {
                return ResponseEntity.notFound().build();
            }
            requireFolderOwnerOrSelf(doc.getFolder());
            boolean ready = doc.getFileData() != null && doc.getFileData().length > 0;
            return ResponseEntity.ok(Map.of(
                    "id", docId,
                    "status", ready ? "READY" : "PROCESSING"
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{username}/{folderId}/documents/{docId}")
    @Transactional
    public ResponseEntity<?> deleteDocument(
            @PathVariable String username,
            @PathVariable Long folderId,
            @PathVariable Long docId) {
        return documentRepo.findById(docId).map(doc -> {
            if (doc.getFolder() == null || !doc.getFolder().getId().equals(folderId)) {
                return ResponseEntity.notFound().build();
            }
            documentRepo.deleteById(docId);
            return ResponseEntity.ok(Map.of("message", "Document deleted."));
        }).orElse(ResponseEntity.notFound().build());
    }
    

    @PutMapping("/{username}/{folderId}/documents/{docId}")
    public ResponseEntity<?> updateDocument(
            @PathVariable String username,
            @PathVariable Long folderId,
            @PathVariable Long docId,
            @RequestBody Map<String, String> body) {
        requireOwnerOrSelf(username);
        return documentRepo.findById(docId).map(doc -> {
            if (doc.getFolder() == null || !doc.getFolder().getId().equals(folderId)) {
                return ResponseEntity.notFound().build();
            }
            requireFolderOwnerOrSelf(doc.getFolder());
            if (body.containsKey("description")) {
                doc.setDescription(body.get("description"));
            }
            documentRepo.save(doc);
            doc.setFileData(null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", doc.getId());
            result.put("originalFileName", doc.getOriginalFileName());
            result.put("fileType", doc.getFileType());
            result.put("fileSize", doc.getFileSize());
            result.put("description", doc.getDescription());
            result.put("uploadedBy", doc.getUploadedBy());
            result.put("uploadedAt", doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : "");
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }
}