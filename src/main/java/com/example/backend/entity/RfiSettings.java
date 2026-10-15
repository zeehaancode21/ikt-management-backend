package com.example.backend.entity;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RfiSettings.java
 * =====================================================================
 * Unified entity that consolidates both RfiSettings and RfiEmailContact
 * tables into a single table. Email contacts for TO and CC are now stored
 * as JSON arrays within this table, minimizing database rows and reducing
 * memory footprint.
 * =====================================================================
 */
@Entity
@Table(name = "rfi_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Slf4j
public class RfiSettings {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * One-to-One relationship with User
     * Each user can have only one RFI settings record
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
    
    /**
     * Gmail email address for sending RFI emails
     */
    @Column(name = "gmail_email", nullable = false, length = 255)
    private String gmailEmail;

    /**
     * Google OAuth Client ID (not secret, stored as plain text)
     */
    @Column(name = "gmail_client_id", length = 500)
    private String gmailClientId;

    /**
     * Google OAuth Client Secret (encrypted)
     */
    @Column(name = "gmail_client_secret", columnDefinition = "LONGTEXT")
    private String gmailClientSecret;
    
    /**
     * Google OAuth refresh token (encrypted)
     */
    @Column(name = "gmail_refresh_token", columnDefinition = "LONGTEXT")
    private String gmailRefreshToken;
    
    /**
     * Token validity status
     */
    @Column(name = "token_valid")
    private Boolean tokenValid = true;
    
    /**
     * Token expiration date
     */
    @Column(name = "token_expiry_date")
    private LocalDateTime tokenExpiryDate;
    
    // ==================== Email Contacts (Merged) ====================
    
    /**
     * JSON array storing TO recipient emails with metadata
     * Format: [{"email":"addr1@example.com","favorite":true,"lastUsedAt":"2026-09-23T10:30:00"}]
     * Stored as JSON to reduce rows and improve performance
     */
    @Column(name = "email_to_contacts", columnDefinition = "JSON")
    private String emailToContactsJson = "[]";
    
    /**
     * JSON array storing CC recipient emails with metadata
     * Same format as TO contacts
     */
    @Column(name = "email_cc_contacts", columnDefinition = "JSON")
    private String emailCcContactsJson = "[]";
    
    /**
     * Timestamp when settings were created
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * Timestamp when settings were last updated
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // ==================== Transient Fields (Not persisted) ====================
    
    @Transient
    private Set<EmailContactData> toContacts;
    
    @Transient
    private Set<EmailContactData> ccContacts;
    
    // IMPORTANT: this is a bare, manually-created ObjectMapper - it does NOT
    // get Spring Boot's auto-configured Jackson setup (JavaTimeModule etc.)
    // the way the app's request/response ObjectMapper bean does. Without
    // JavaTimeModule registered, writeValueAsString() throws on
    // EmailContactData.lastUsedAt (a LocalDateTime) EVERY time, which was
    // being silently swallowed by serializeContacts()'s catch block below,
    // always falling back to "[]" - meaning nothing was ever actually
    // persisted to email_to_contacts/email_cc_contacts, even though the
    // in-memory response looked correct.
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    // ==================== Lifecycle Methods ====================
    
    @PreUpdate
    @PrePersist
    protected void onUpdate() {
        // Safety net only - the JSON columns are now kept in sync
        // immediately by syncToContactsJson()/syncCcContactsJson()
        // every time the transient sets are mutated (see below).
        // Relying on this callback alone does NOT work: Hibernate's
        // dirty-checking is based on the persistent column fields,
        // not the @Transient toContacts/ccContacts sets, so mutating
        // only the transient sets never marks the entity dirty and
        // this callback would never fire, silently dropping the save.
        if (toContacts != null) {
            emailToContactsJson = serializeContacts(toContacts);
        }
        if (ccContacts != null) {
            emailCcContactsJson = serializeContacts(ccContacts);
        }
    }

    /**
     * Re-serializes the TO set into the persistent JSON column.
     * MUST be called at the end of every method that mutates toContacts,
     * otherwise Hibernate won't detect the entity as dirty and the
     * change will silently never be persisted.
     */
    private void syncToContactsJson() {
        emailToContactsJson = serializeContacts(toContacts);
    }

    /**
     * Re-serializes the CC set into the persistent JSON column.
     * MUST be called at the end of every method that mutates ccContacts,
     * otherwise Hibernate won't detect the entity as dirty and the
     * change will silently never be persisted.
     */
    private void syncCcContactsJson() {
        emailCcContactsJson = serializeContacts(ccContacts);
    }
    
    @PostLoad
    protected void onLoad() {
        // Deserialize JSON to sets after loading from database
        this.toContacts = deserializeContacts(emailToContactsJson);
        this.ccContacts = deserializeContacts(emailCcContactsJson);
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Serialize Set<EmailContactData> to JSON string
     */
    private String serializeContacts(Set<EmailContactData> contacts) {
        try {
            if (contacts == null || contacts.isEmpty()) {
                return "[]";
            }
            // Sort by email for consistency
            List<EmailContactData> sortedList = contacts.stream()
                    .sorted(Comparator.comparing(EmailContactData::getEmail))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(sortedList);
        } catch (Exception e) {
            // Do NOT silently swallow this - a failure here means whatever
            // was just added/favorited/removed will never actually reach
            // the database, while callers keep seeing correct-looking
            // responses built from the in-memory (transient) set. Surface
            // it loudly instead of returning "[]" as if nothing happened.
            log.error("Failed to serialize email contacts to JSON - " +
                    "this contact change will NOT be persisted!", e);
            throw new RuntimeException("Failed to serialize email contacts", e);
        }
    }
    
    /**
     * Deserialize JSON string to Set<EmailContactData>
     */
    private Set<EmailContactData> deserializeContacts(String json) {
        try {
            if (json == null || json.isEmpty() || "[]".equals(json)) {
                return new HashSet<>();
            }
            List<EmailContactData> list = objectMapper.readValue(json, 
                    new TypeReference<List<EmailContactData>>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            log.error("Failed to deserialize email contacts JSON: {}", json, e);
            return new HashSet<>();
        }
    }
    
    // ==================== Business Methods ====================
    
    /**
     * Get all TO contacts (favorited or not), sorted by email
     */
    public List<EmailContactData> getAllToContacts() {
        return toContacts == null ? new ArrayList<>() : 
               toContacts.stream()
                   .sorted(Comparator.comparing(EmailContactData::getEmail))
                   .collect(Collectors.toList());
    }
    
    /**
     * Get only favorited TO contacts
     */
    public List<EmailContactData> getFavoriteToContacts() {
        return toContacts == null ? new ArrayList<>() :
               toContacts.stream()
                   .filter(EmailContactData::isFavorite)
                   .sorted(Comparator.comparing(EmailContactData::getEmail))
                   .collect(Collectors.toList());
    }
    
    /**
     * Get all CC contacts (favorited or not), sorted by email
     */
    public List<EmailContactData> getAllCcContacts() {
        return ccContacts == null ? new ArrayList<>() :
               ccContacts.stream()
                   .sorted(Comparator.comparing(EmailContactData::getEmail))
                   .collect(Collectors.toList());
    }
    
    /**
     * Get only favorited CC contacts
     */
    public List<EmailContactData> getFavoriteCcContacts() {
        return ccContacts == null ? new ArrayList<>() :
               ccContacts.stream()
                   .filter(EmailContactData::isFavorite)
                   .sorted(Comparator.comparing(EmailContactData::getEmail))
                   .collect(Collectors.toList());
    }
    
    /**
     * Add or update a TO contact
     */
    public void addOrUpdateToContact(String email, boolean favorite) {
        if (toContacts == null) {
            toContacts = new HashSet<>();
        }
        String normalizedEmail = email.trim().toLowerCase();
        
        // Remove if exists (case-insensitive)
        toContacts.removeIf(c -> c.getEmail().equalsIgnoreCase(normalizedEmail));
        
        // Add updated contact
        toContacts.add(new EmailContactData(normalizedEmail, favorite, LocalDateTime.now()));
        syncToContactsJson();
    }
    
    /**
     * Add or update a CC contact
     */
    public void addOrUpdateCcContact(String email, boolean favorite) {
        if (ccContacts == null) {
            ccContacts = new HashSet<>();
        }
        String normalizedEmail = email.trim().toLowerCase();
        
        // Remove if exists (case-insensitive)
        ccContacts.removeIf(c -> c.getEmail().equalsIgnoreCase(normalizedEmail));
        
        // Add updated contact
        ccContacts.add(new EmailContactData(normalizedEmail, favorite, LocalDateTime.now()));
        syncCcContactsJson();
    }
    
    /**
     * Record usage by updating lastUsedAt timestamp
     */
    public void recordToContactUsage(String email) {
        if (toContacts == null) {
            toContacts = new HashSet<>();
        }
        String normalizedEmail = email.trim().toLowerCase();
        
        EmailContactData existing = toContacts.stream()
                .filter(c -> c.getEmail().equalsIgnoreCase(normalizedEmail))
                .findFirst()
                .orElse(null);
        
        if (existing != null) {
            toContacts.remove(existing);
            existing.setLastUsedAt(LocalDateTime.now());
            toContacts.add(existing);
        } else {
            toContacts.add(new EmailContactData(normalizedEmail, false, LocalDateTime.now()));
        }
        syncToContactsJson();
    }
    
    /**
     * Record usage by updating lastUsedAt timestamp for CC
     */
    public void recordCcContactUsage(String email) {
        if (ccContacts == null) {
            ccContacts = new HashSet<>();
        }
        String normalizedEmail = email.trim().toLowerCase();
        
        EmailContactData existing = ccContacts.stream()
                .filter(c -> c.getEmail().equalsIgnoreCase(normalizedEmail))
                .findFirst()
                .orElse(null);
        
        if (existing != null) {
            ccContacts.remove(existing);
            existing.setLastUsedAt(LocalDateTime.now());
            ccContacts.add(existing);
        } else {
            ccContacts.add(new EmailContactData(normalizedEmail, false, LocalDateTime.now()));
        }
        syncCcContactsJson();
    }
    
    /**
     * Set favorite status for TO contact
     */
    public void setToContactFavorite(String email, boolean favorite) {
        if (toContacts == null) {
            toContacts = new HashSet<>();
        }
        String normalizedEmail = email.trim().toLowerCase();
        
        EmailContactData existing = toContacts.stream()
                .filter(c -> c.getEmail().equalsIgnoreCase(normalizedEmail))
                .findFirst()
                .orElse(null);
        
        if (existing != null) {
            toContacts.remove(existing);
            existing.setFavorite(favorite);
            toContacts.add(existing);
        } else {
            toContacts.add(new EmailContactData(normalizedEmail, favorite, LocalDateTime.now()));
        }
        syncToContactsJson();
    }
    
    /**
     * Set favorite status for CC contact
     */
    public void setCcContactFavorite(String email, boolean favorite) {
        if (ccContacts == null) {
            ccContacts = new HashSet<>();
        }
        String normalizedEmail = email.trim().toLowerCase();
        
        EmailContactData existing = ccContacts.stream()
                .filter(c -> c.getEmail().equalsIgnoreCase(normalizedEmail))
                .findFirst()
                .orElse(null);
        
        if (existing != null) {
            ccContacts.remove(existing);
            existing.setFavorite(favorite);
            ccContacts.add(existing);
        } else {
            ccContacts.add(new EmailContactData(normalizedEmail, favorite, LocalDateTime.now()));
        }
        syncCcContactsJson();
    }
    
    /**
     * Remove a TO contact
     */
    public void removeToContact(String email) {
        if (toContacts != null) {
            toContacts.removeIf(c -> c.getEmail().equalsIgnoreCase(email));
            syncToContactsJson();
        }
    }
    
    /**
     * Remove a CC contact
     */
    public void removeCcContact(String email) {
        if (ccContacts != null) {
            ccContacts.removeIf(c -> c.getEmail().equalsIgnoreCase(email));
            syncCcContactsJson();
        }
    }
    
    /**
     * Check if token is still valid
     */
    public boolean isTokenValid() {
        if (tokenValid == null || !tokenValid) {
            return false;
        }
        
        if (tokenExpiryDate != null && LocalDateTime.now().isAfter(tokenExpiryDate)) {
            return false;
        }
        
        return true;
    }
}