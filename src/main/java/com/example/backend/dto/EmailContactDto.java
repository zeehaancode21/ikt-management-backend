package com.example.backend.dto;

import com.example.backend.entity.EmailContactData;
import com.example.backend.entity.RfiEmailAddressType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * EmailContactDto.java
 * =====================================================================
 * Data Transfer Object representing a single email contact.
 * Replaces the old RfiEmailContact entity responses with a cleaner API.
 * =====================================================================
 */
@Data
@NoArgsConstructor
@Builder
public class EmailContactDto {
    
    /**
     * The email address
     */
    @JsonProperty("email")
    private String email;
    
    /**
     * Email type: TO or CC
     */
    @JsonProperty("type")
    private String type;
    
    /**
     * Whether this email is marked as favorite
     */
    @JsonProperty("favorite")
    private boolean favorite;
    
    /**
     * Last time this email was used for sending
     */
    @JsonProperty("lastUsedAt")
    private LocalDateTime lastUsedAt;
    
    /**
     * Constructor helper
     */
    public EmailContactDto(String email, String type, boolean favorite, LocalDateTime lastUsedAt) {
        this.email = email;
        this.type = type;
        this.favorite = favorite;
        this.lastUsedAt = lastUsedAt;
    }
    
    /**
     * Convert from EmailContactData entity to DTO
     */
    public static EmailContactDto fromEntity(EmailContactData entity, RfiEmailAddressType type) {
        return EmailContactDto.builder()
            .email(entity.getEmail())
            .type(type.toString())
            .favorite(entity.isFavorite())
            .lastUsedAt(entity.getLastUsedAt())
            .build();
    }
    
    /**
     * Convert to EmailContactData entity
     */
    public EmailContactData toEntity() {
        return new EmailContactData(
            this.email,
            this.favorite,
            this.lastUsedAt
        );
    }
}