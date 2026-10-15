package com.example.backend.dto;

import java.time.LocalDateTime;

/**
 * Document metadata WITHOUT the file bytes.
 *
 * Loading the Document entity always pulls the whole LONGBLOB (up to 20 MB) into the
 * heap. Lists, status polls and metadata edits only need these columns, so the
 * repository queries select just them (Spring Data interface projection; each
 * accessor matches an "as ..." alias in DocumentRepository).
 */
public interface DocumentMetaView {
    Long getId();
    String getFileName();
    String getOriginalFileName();
    String getFileType();
    Long getFileSize();
    String getDescription();
    String getCategory();
    String getProjectName();
    String getUploadedBy();
    String getUploadStatus();
    LocalDateTime getUploadedAt();
    /** null for documents that are not inside a folder. */
    Long getFolderId();
}