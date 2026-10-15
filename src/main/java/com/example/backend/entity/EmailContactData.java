package com.example.backend.entity;

import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * EmailContactData.java
 * =====================================================================
 * Lightweight data class to represent an email contact within the
 * merged RfiSettings table. Each email is stored in JSON format with
 * its metadata (favorite flag and last used timestamp).
 * 
 * This replaces individual rows in the old rfi_email_contacts table
 * with a structured data model that fits perfectly within JSON columns.
 * =====================================================================
 */
@Data
@NoArgsConstructor
@Builder
public class EmailContactData {
    
    /**
     * Email address (stored in lowercase for case-insensitive matching)
     */
    @JsonProperty("email")
    private String email;
    
    /**
     * Whether this email is marked as favorite
     * Only favorites appear in dropdowns on the "Create RFI" form
     */
    @JsonProperty("favorite")
    private boolean favorite;
    
    /**
     * Last time this email was used for sending
     * Helps users find recently used contacts
     */
    @JsonProperty("lastUsedAt")
    private LocalDateTime lastUsedAt;
    
    /**
     * Constructor for common case: creating new contact with email
     */
    public EmailContactData(String email, boolean favorite) {
        this.email = email.trim().toLowerCase();
        this.favorite = favorite;
        this.lastUsedAt = LocalDateTime.now();
    }
    
    /**
     * Constructor with all fields
     */
    public EmailContactData(String email, boolean favorite, LocalDateTime lastUsedAt) {
        this.email = email.trim().toLowerCase();
        this.favorite = favorite;
        this.lastUsedAt = lastUsedAt != null ? lastUsedAt : LocalDateTime.now();
    }
    
    /**
     * Validate email format
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String trimmed = email.trim();
        return trimmed.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }
    
    /**
     * Normalize email (lowercase, trimmed)
     */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmailContactData that = (EmailContactData) o;
        // Email comparison is case-insensitive
        return email != null && email.equalsIgnoreCase(that.email);
    }
    
    @Override
    public int hashCode() {
        // Use lowercase email for consistent hashing
        return email != null ? email.toLowerCase().hashCode() : 0;
    }
    
    @Override
    public String toString() {
        return "EmailContactData{" +
                "email='" + email + '\'' +
                ", favorite=" + favorite +
                ", lastUsedAt=" + lastUsedAt +
                '}';
    }
}