package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RfiSettingsDto.java
 * =====================================================================
 * Data Transfer Object for RFI Settings including merged email contacts.
 * This now includes both Gmail configuration and email contact information
 * in a single DTO, simplifying API responses.
 * =====================================================================
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RfiSettingsDto {
    
    @JsonProperty("id")
    private Long id;
    
    @JsonProperty("gmailEmail")
    private String gmailEmail;

    @JsonProperty("gmailClientId")
    private String gmailClientId;

    @JsonProperty("gmailClientSecret")
    private String gmailClientSecret;
    
    @JsonProperty("gmailRefreshToken")
    private String gmailRefreshToken;
    
    @JsonProperty("tokenValid")
    private Boolean tokenValid;
    
    @JsonProperty("tokenExpiryDate")
    private LocalDateTime tokenExpiryDate;
    
    /**
     * List of all TO contacts (favorited and unfavorited)
     * Automatically fetched when retrieving settings
     */
    @JsonProperty("toContacts")
    private List<EmailContactDto> toContacts;
    
    /**
     * List of all CC contacts (favorited and unfavorited)
     * Automatically fetched when retrieving settings
     */
    @JsonProperty("ccContacts")
    private List<EmailContactDto> ccContacts;
}