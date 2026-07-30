package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * Structured bank details (account number, IFSC, bank name, account holder
 * name). Each sensitive field is encrypted independently with AES-256-GCM
 * (see EncryptionService) before being stored — the columns hold Base64
 * ciphertext, never plaintext.
 */
@Entity
@Table(name = "employee_bank_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeBankDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_username", nullable = false, unique = true)
    private String employeeUsername;

    @JsonIgnore
    @Column(name = "account_holder_name_enc", length = 1000)
    private String accountHolderNameEnc;

    @JsonIgnore
    @Column(name = "account_number_enc", length = 1000)
    private String accountNumberEnc;

    @JsonIgnore
    @Column(name = "ifsc_enc", length = 1000)
    private String ifscEnc;

    @JsonIgnore
    @Column(name = "bank_name_enc", length = 1000)
    private String bankNameEnc;

    private String updatedBy;
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}