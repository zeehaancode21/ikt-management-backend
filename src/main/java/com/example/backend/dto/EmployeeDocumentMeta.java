package com.example.backend.dto;

import com.example.backend.entity.DocumentType;

import java.time.LocalDateTime;

/**
 * KYC/vault document metadata WITHOUT the encrypted file bytes.
 * Status checklists and vault listings only need these columns, but loading the
 * EmployeeDocument entity always pulls the whole LONGBLOB (up to 10 MB) per document.
 */
public interface EmployeeDocumentMeta {
    Long getId();
    String getEmployeeUsername();
    DocumentType getDocType();
    String getOriginalFileName();
    String getContentType();
    LocalDateTime getUploadedAt();
}