package com.example.backend.controller;

import com.example.backend.dto.DocumentMetaView;
import com.example.backend.dto.FolderRowView;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // ── Folder tree without loading file bytes ────────────────────────────────
    // Folder.documents / Folder.subFolders are EAGER, so loading Folder entities pulls EVERY
    // document (file bytes included) of the whole tree into memory, only for the bytes to be
    // nulled out afterwards. These helpers build the exact same JSON from metadata-only queries:
    // one query per tree level for folders, plus one query for all their documents.
    private List<Map<String, Object>> buildTrees(List<FolderRowView> roots) {
        Map<Long, FolderRowView> rows = new LinkedHashMap<>();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        List<Long> frontier = new ArrayList<>();
        for (FolderRowView r : roots) {
            if (rows.putIfAbsent(r.getId(), r) == null) frontier.add(r.getId());
        }
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (FolderRowView kid : folderRepo.findChildRows(frontier)) {
                if (rows.containsKey(kid.getId())) continue; // guards against a corrupt cycle
                rows.put(kid.getId(), kid);
                childrenOf.computeIfAbsent(kid.getParentId(), k -> new ArrayList<>()).add(kid.getId());
                next.add(kid.getId());
            }
            frontier = next;
        }

        Map<Long, List<DocumentMetaView>> docsOf = new HashMap<>();
        if (!rows.isEmpty()) {
            for (DocumentMetaView d : documentRepo.findMetaByFolderIds(rows.keySet())) {
                docsOf.computeIfAbsent(d.getFolderId(), k -> new ArrayList<>()).add(d);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (FolderRowView r : roots) {
            result.add(folderJson(r.getId(), rows, childrenOf, docsOf));
        }
        return result;
    }

    /** Same JSON the Folder entity produced (null fields omitted; "leaf"/"branch" are its isLeaf()/isBranch()). */
    private Map<String, Object> folderJson(Long id, Map<Long, FolderRowView> rows,
                                           Map<Long, List<Long>> childrenOf,
                                           Map<Long, List<DocumentMetaView>> docsOf) {
        FolderRowView r = rows.get(id);
        List<Long> kidIds = childrenOf.getOrDefault(id, List.of());
        List<DocumentMetaView> docs = docsOf.getOrDefault(id, List.of());

        Map<String, Object> m = new LinkedHashMap<>();
        putIfNotNull(m, "id", r.getId());
        putIfNotNull(m, "name", r.getName());
        putIfNotNull(m, "description", r.getDescription());
        putIfNotNull(m, "createdBy", r.getCreatedBy());
        putIfNotNull(m, "createdAt", r.getCreatedAt());
        putIfNotNull(m, "parentId", r.getParentId());

        List<Map<String, Object>> subFolders = new ArrayList<>();
        for (Long kidId : kidIds) subFolders.add(folderJson(kidId, rows, childrenOf, docsOf));
        m.put("subFolders", subFolders);

        List<Map<String, Object>> documents = new ArrayList<>();
        for (DocumentMetaView d : docs) documents.add(folderDocJson(d));
        m.put("documents", documents);

        m.put("leaf", !docs.isEmpty());
        m.put("branch", !kidIds.isEmpty());
        return m;
    }

    // =========================================================================
    // FOLDER CRUD
    // =========================================================================

    @GetMapping("/{username}")
    public ResponseEntity<List<Map<String, Object>>> getUserFolders(@PathVariable String username) {
        requireOwnerOrSelf(username);
        try {
            return ResponseEntity.ok(buildTrees(folderRepo.findRootRows(username)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{username}/{folderId}")
    public ResponseEntity<?> getFolder(
            @PathVariable String username,
            @PathVariable Long folderId) {
        requireOwnerOrSelf(username);
        return folderRepo.findRowById(folderId).map(row -> {
            requireOwnerOrSelf(row.getCreatedBy());
            return ResponseEntity.ok(buildTrees(List.of(row)).get(0));
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
            // findById(parent) would load every file inside the parent (EAGER) just for these checks.
            if (!folderRepo.existsById(parentId)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Parent folder not found."));
            }
            requireOwnerOrSelf(folderRepo.findCreatedByById(parentId).orElse(null));

            if (documentRepo.existsByFolder_Id(parentId)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "Cannot create a sub-folder inside a folder that already contains files. "
                    + "Upload files OR create sub-folders — not both."
                ));
            }
            parent = folderRepo.getReferenceById(parentId); // reference only, nothing loaded
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
        return folderRepo.findRowById(id).map(row -> {
            requireOwnerOrSelf(row.getCreatedBy());
            String name = row.getName();
            String description = row.getDescription();
            if (body.containsKey("name") && !body.get("name").isBlank()) {
                name = body.get("name").trim();
            }
            if (body.containsKey("description")) {
                description = body.get("description");
            }
            // Was findById + save: loaded every file of the folder and re-saved the whole tree.
            folderRepo.updateNameAndDescription(id, name, description);
            FolderRowView updated = folderRepo.findRowById(id).orElse(row);
            return ResponseEntity.ok(buildTrees(List.of(updated)).get(0));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteFolder(@PathVariable Long id) {
        // Old version: findById + deleteById loaded the whole subtree WITH every file's bytes,
        // then deleted the documents one by one through the cascade. Same result, done in bulk:
        // one query per tree level to find the folders, one DELETE for all their documents, then
        // the folders deepest-first (a parent row can't go before its children). All in one
        // transaction, exactly like deleteById() was.
        var rowOpt = folderRepo.findRowById(id);
        if (rowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        requireOwnerOrSelf(rowOpt.get().getCreatedBy());

        List<List<Long>> levels = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        seen.add(id);
        List<Long> frontier = List.of(id);
        while (!frontier.isEmpty()) {
            levels.add(frontier);
            List<Long> next = new ArrayList<>();
            for (FolderRowView kid : folderRepo.findChildRows(frontier)) {
                if (seen.add(kid.getId())) next.add(kid.getId()); // guards against a corrupt cycle
            }
            frontier = next;
        }

        documentRepo.deleteAllInFolders(seen);
        for (int i = levels.size() - 1; i >= 0; i--) {
            folderRepo.deleteAllByIdsIn(levels.get(i));
        }
        return ResponseEntity.ok(Map.of("message", "Folder deleted."));
    }

    // =========================================================================
    // DOCUMENT CRUD
    // =========================================================================

    /** Same JSON the Document entity produced (fileData was always nulled; null fields are omitted). */
    private Map<String, Object> folderDocJson(DocumentMetaView d) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIfNotNull(m, "id", d.getId());
        putIfNotNull(m, "fileName", d.getFileName());
        putIfNotNull(m, "originalFileName", d.getOriginalFileName());
        putIfNotNull(m, "fileType", d.getFileType());
        putIfNotNull(m, "fileSize", d.getFileSize());
        putIfNotNull(m, "description", d.getDescription());
        putIfNotNull(m, "category", d.getCategory());
        putIfNotNull(m, "projectName", d.getProjectName());
        putIfNotNull(m, "uploadedBy", d.getUploadedBy());
        putIfNotNull(m, "uploadStatus", d.getUploadStatus());
        putIfNotNull(m, "uploadedAt", d.getUploadedAt());
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }

    @GetMapping("/{username}/{folderId}/documents")
    public ResponseEntity<?> getDocuments(
            @PathVariable String username,
            @PathVariable Long folderId) {
        requireOwnerOrSelf(username);
        // folderRepo.findById() would load every document of this folder WITH its file bytes
        // (EAGER collection) only for them to be thrown away below. Read what is needed instead.
        if (!folderRepo.existsById(folderId)) {
            return ResponseEntity.notFound().build();
        }
        requireOwnerOrSelf(folderRepo.findCreatedByById(folderId).orElse(null));
        List<Map<String, Object>> docs = documentRepo.findMetaByFolderId(folderId).stream()
                .map(this::folderDocJson)
                .toList();
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

    @Transactional(readOnly = true)
    @GetMapping("/{username}/{folderId}/documents/{docId}/status")
    public ResponseEntity<?> documentStatus(
            @PathVariable String username,
            @PathVariable Long folderId,
            @PathVariable Long docId) {
        requireOwnerOrSelf(username);
        // Polled while an upload finishes: was loading the whole file each time.
        return documentRepo.findMetaById(docId).map(doc -> {
            if (doc.getFolderId() == null || !doc.getFolderId().equals(folderId)) {
                return ResponseEntity.notFound().build();
            }
            requireOwnerOrSelf(folderRepo.findCreatedByById(folderId).orElse(null));
            boolean ready = documentRepo.countWithFileData(docId) > 0;
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
        return documentRepo.findMetaById(docId).map(doc -> {
            if (doc.getFolderId() == null || !doc.getFolderId().equals(folderId)) {
                return ResponseEntity.notFound().build();
            }
            documentRepo.deleteDocumentById(docId); // single DELETE, file bytes not loaded
            return ResponseEntity.ok(Map.of("message", "Document deleted."));
        }).orElse(ResponseEntity.notFound().build());
    }
    

    @Transactional
    @PutMapping("/{username}/{folderId}/documents/{docId}")
    public ResponseEntity<?> updateDocument(
            @PathVariable String username,
            @PathVariable Long folderId,
            @PathVariable Long docId,
            @RequestBody Map<String, String> body) {
        requireOwnerOrSelf(username);
        return documentRepo.findMetaById(docId).map(found -> {
            if (found.getFolderId() == null || !found.getFolderId().equals(folderId)) {
                return ResponseEntity.notFound().build();
            }
            requireOwnerOrSelf(folderRepo.findCreatedByById(folderId).orElse(null));
            // Old version loaded the file bytes and re-wrote them to the DB for a description edit.
            if (body.containsKey("description")) {
                documentRepo.updateDescription(docId, body.get("description"));
            }
            DocumentMetaView doc = documentRepo.findMetaById(docId).orElse(found);

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