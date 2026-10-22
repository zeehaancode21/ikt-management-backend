package com.example.backend.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

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
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
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