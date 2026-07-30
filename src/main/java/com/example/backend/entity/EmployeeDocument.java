package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * One row per (employee, document type). The file bytes stored in
 * encryptedFileData are ALWAYS ciphertext (AES-256-GCM) — see EncryptionService.
 * Nobody — not even a database admin — can read the raw file without the
 * server's encryption key, and owners additionally need a verified TOTP
 * "vault token" to decrypt it through the application (see VaultController).
 */
@Entity
@Table(name = "employee_documents", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"employee_username", "doc_type"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_username", nullable = false)
    private String employeeUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocumentType docType;

    private String originalFileName;
    private String contentType;
    private Long fileSize;

    // Ciphertext only: [12-byte IV][GCM ciphertext+tag]. Never store plaintext.
    @Lob
    @Column(name = "encrypted_file_data", columnDefinition = "LONGBLOB")
    @JsonIgnore
    private byte[] encryptedFileData;

    private String uploadedBy;
    private LocalDateTime uploadedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        uploadedAt = LocalDateTime.now();
    }
}