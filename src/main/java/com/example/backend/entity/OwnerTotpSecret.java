package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * Stores the TOTP (Time-based One-Time Password) secret used to gate access
 * to the confidential document vault. The secret itself is encrypted at rest
 * with AES-256-GCM — only decrypted in memory at the moment a code needs
 * verifying.
 */
@Entity
@Table(name = "owner_totp_secrets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnerTotpSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @JsonIgnore
    @Column(name = "secret_enc", nullable = false, length = 1000)
    private String secretEnc;

    @Column(nullable = false)
    private boolean enabled = false;

    private LocalDateTime createdAt;
    private LocalDateTime enabledAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}