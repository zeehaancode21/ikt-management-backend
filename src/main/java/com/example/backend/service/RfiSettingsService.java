package com.example.backend.service;

import com.example.backend.dto.RfiSettingsDto;
import com.example.backend.dto.EmailContactDto;
import com.example.backend.entity.RfiSettings;
import com.example.backend.entity.RfiEmailAddressType;
import com.example.backend.entity.User;
import org.springframework.transaction.annotation.Propagation;
import com.example.backend.repository.RfiSettingsRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.Exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * RfiSettingsService.java
 * =====================================================================
 * MERGED Service combining the functionality of both:
 * - RfiSettingsService (token, Gmail config)
 * - RfiEmailContactService (TO/CC email addresses, favorites)
 * 
 * All operations now occur on a single RfiSettings entity, minimizing
 * database rows and improving transaction efficiency. Email contacts
 * are stored as JSON within RfiSettings, not in a separate table.
 * =====================================================================
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RfiSettingsService {
    
    @Autowired 
    private  RfiSettingsRepository rfiSettingsRepository;
    @Autowired 
    private  UserRepository userRepository;
    
    // Encryption key for storing refresh tokens securely
    // IMPORTANT: Change this to your own secure key in production!
    private static final String ENCRYPTION_KEY = "MySecureKeyFor16"; // Must be 16 characters for AES-128
    private static final String ALGORITHM = "AES";
    
    private static final Pattern EMAIL_PATTERN
            = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    
    // ==================== Settings Management ====================
    
    /**
     * Get RFI settings for a user
     */
    @Transactional(readOnly = true)
    public RfiSettingsDto getSettingsByUserId(Long userId) {
        RfiSettings settings = getRfiSettingsOrThrow(userId);
        return mapToDto(settings);
    }
    
    /**
     * Save or update RFI settings (Gmail config only)
     */
    @Transactional
    public RfiSettingsDto saveSettings(Long userId, RfiSettingsDto settingsDto) {
        log.info("Saving RFI settings for user: {}", userId);
        
        // Get or create settings
        RfiSettings settings = rfiSettingsRepository.findByUserId(userId)
            .orElseGet(() -> {
                RfiSettings newSettings = new RfiSettings();
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
                newSettings.setUser(user);
                return newSettings;
            });
        
        // Update Gmail fields
        settings.setGmailEmail(settingsDto.getGmailEmail());
        settings.setGmailClientId(settingsDto.getGmailClientId());
        
        // Only update Client Secret if provided (not null/blank)
        if (settingsDto.getGmailClientSecret() != null && !settingsDto.getGmailClientSecret().isBlank()) {
            settings.setGmailClientSecret(encryptToken(settingsDto.getGmailClientSecret()));
        }
        
        // Only update refresh token if provided
        if (settingsDto.getGmailRefreshToken() != null && !settingsDto.getGmailRefreshToken().isBlank()) {
            String encryptedToken = encryptToken(settingsDto.getGmailRefreshToken());
            settings.setGmailRefreshToken(encryptedToken);
            settings.setTokenValid(true);
            settings.setTokenExpiryDate(LocalDateTime.now().plusMonths(6));
        }
        
        RfiSettings saved = rfiSettingsRepository.save(settings);
        log.info("RFI settings saved successfully for user: {}", userId);
        
        return mapToDto(saved);
    }
    
    /**
     * Validate if the refresh token is still valid
     */
    public boolean validateToken(String encryptedToken, String gmailEmail) {
        try {
            if (encryptedToken == null || encryptedToken.isEmpty()) {
                return false;
            }
            
            if (gmailEmail == null || gmailEmail.isEmpty()) {
                return false;
            }
            
            // Try to decrypt to verify token format
            String decryptedToken = decryptToken(encryptedToken);
            
            // Token should be long enough for OAuth refresh token
            return decryptedToken != null && !decryptedToken.isEmpty() && decryptedToken.length() > 10;
            
        } catch (Exception e) {
            log.error("Token validation failed", e);
            return false;
        }
    }
    
    /**
     * Clear the stored token for a user
     */
    @Transactional
    public void clearToken(Long userId) {
        log.info("Clearing token for user: {}", userId);
        
        RfiSettings settings = getRfiSettingsOrThrow(userId);
        
        settings.setGmailRefreshToken(null);
        settings.setTokenValid(false);
        
        rfiSettingsRepository.save(settings);
        log.info("Token cleared for user: {}", userId);
    }
    
    /**
     * Get decrypted token for use in sending emails
     */
    public String getDecryptedToken(Long userId) {
        RfiSettings settings = getRfiSettingsOrThrow(userId);
        
        if (settings.getGmailRefreshToken() == null) {
            throw new RuntimeException("No refresh token configured for user");
        }
        
        if (!settings.getTokenValid()) {
            throw new RuntimeException("Token is invalid or expired");
        }
        
        return decryptToken(settings.getGmailRefreshToken());
    }
    
    /**
     * Get Gmail email for a user
     */
    public String getGmailEmail(Long userId) {
        RfiSettings settings = getRfiSettingsOrThrow(userId);
        return settings.getGmailEmail();
    }

    /**
     * Get the user's own OAuth Client ID (not secret, stored as plain text)
     */
    public String getGmailClientId(Long userId) {
        RfiSettings settings = getRfiSettingsOrThrow(userId);
        return settings.getGmailClientId();
    }

    /**
     * Get the user's own decrypted OAuth Client Secret
     */
    public String getDecryptedClientSecret(Long userId) {
        RfiSettings settings = getRfiSettingsOrThrow(userId);

        if (settings.getGmailClientSecret() == null) {
            throw new RuntimeException("No Gmail Client Secret configured for user");
        }

        return decryptToken(settings.getGmailClientSecret());
    }
    
    /**
     * Mark token as invalid (when API returns invalid_grant error)
     */
    @Transactional
    public void markTokenAsInvalid(Long userId) {
        RfiSettings settings = getRfiSettingsOrThrow(userId);
        settings.setTokenValid(false);
        rfiSettingsRepository.save(settings);
        log.warn("Token marked as invalid for user: {}", userId);
    }
    
    /**
     * Check if settings exist for this user
     */
    public boolean settingsExist(Long userId) {
        return rfiSettingsRepository.existsByUserId(userId);
    }

    /**
     * Check if user has valid RFI settings configured
     */
    public boolean hasValidSettings(Long userId) {
        try {
            RfiSettings settings = rfiSettingsRepository.findByUserId(userId).orElse(null);
            if (settings == null) {
                return false;
            }
            return settings.getGmailEmail() != null &&
                   settings.getGmailClientId() != null && !settings.getGmailClientId().isBlank() &&
                   settings.getGmailClientSecret() != null &&
                   settings.getGmailRefreshToken() != null && 
                   settings.isTokenValid();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Delete all settings for a user (including email contacts)
     */
    @Transactional
    public void deleteSettings(Long userId) {
        log.info("Deleting RFI settings for user: {}", userId);
        rfiSettingsRepository.deleteByUserId(userId);
        log.info("RFI settings deleted for user: {}", userId);
    }
    
    // ==================== Email Contact Management (Merged) ====================
    
    /**
     * Get all TO contacts (favorited or not), sorted A-Z
     */
    @Transactional(readOnly = true)
    public List<EmailContactDto> getAllToContacts(Long userId) {
        RfiSettings settings = getOrCreateRfiSettings(userId);
        return settings.getAllToContacts().stream()
            .map(contact -> EmailContactDto.fromEntity(contact, RfiEmailAddressType.TO))
            .collect(Collectors.toList());
    }
    
    /**
     * Get only favorited TO contacts
     */
    @Transactional(readOnly = true)
    public List<EmailContactDto> getFavoriteToContacts(Long userId) {
        RfiSettings settings = getOrCreateRfiSettings(userId);
        return settings.getFavoriteToContacts().stream()
            .map(contact -> EmailContactDto.fromEntity(contact, RfiEmailAddressType.TO))
            .collect(Collectors.toList());
    }
    
    /**
     * Get all CC contacts (favorited or not), sorted A-Z
     */
    @Transactional(readOnly = true)
    public List<EmailContactDto> getAllCcContacts(Long userId) {
        RfiSettings settings = getOrCreateRfiSettings(userId);
        return settings.getAllCcContacts().stream()
            .map(contact -> EmailContactDto.fromEntity(contact, RfiEmailAddressType.CC))
            .collect(Collectors.toList());
    }
    
    /**
     * Get only favorited CC contacts
     */
    @Transactional(readOnly = true)
    public List<EmailContactDto> getFavoriteCcContacts(Long userId) {
        RfiSettings settings = getOrCreateRfiSettings(userId);
        return settings.getFavoriteCcContacts().stream()
            .map(contact -> EmailContactDto.fromEntity(contact, RfiEmailAddressType.CC))
            .collect(Collectors.toList());
    }
    
    /**
     * Get all contacts of a specific type
     */
    @Transactional(readOnly = true)
    public List<EmailContactDto> getAllContacts(Long userId, RfiEmailAddressType type) {
        if (type == RfiEmailAddressType.TO) {
            return getAllToContacts(userId);
        } else {
            return getAllCcContacts(userId);
        }
    }
    
    /**
     * Get only favorited contacts of a specific type
     */
    @Transactional(readOnly = true)
    public List<EmailContactDto> getFavoriteContacts(Long userId, RfiEmailAddressType type) {
        if (type == RfiEmailAddressType.TO) {
            return getFavoriteToContacts(userId);
        } else {
            return getFavoriteCcContacts(userId);
        }
    }
    
    /**
     * Record that an email was used as a TO recipient
     * This updates lastUsedAt and ensures the contact exists
     * Silently fails for invalid emails (they would've failed upstream)
     */
    @Transactional
    public void recordToContactUsage(Long userId, String email) {
        try {
            String normalized = normalizeEmail(email);
            if (!isValidEmail(normalized)) {
                return; // Silently ignore invalid emails
            }
            
            RfiSettings settings = rfiSettingsRepository.findByUserId(userId)
                .orElse(null);
            if (settings == null) {
                return; // Silently ignore if settings don't exist yet
            }
            
            settings.recordToContactUsage(normalized);
            rfiSettingsRepository.save(settings);
            log.debug("Recorded TO contact usage for user: {}, email: {}", userId, normalized);
        } catch (Exception e) {
            log.debug("Failed to record TO contact usage", e);
        }
    }
    
    /**
     * Record that an email was used as a CC recipient
     */
    @Transactional
    public void recordCcContactUsage(Long userId, String email) {
        try {
            String normalized = normalizeEmail(email);
            if (!isValidEmail(normalized)) {
                return; // Silently ignore invalid emails
            }
            
            RfiSettings settings = rfiSettingsRepository.findByUserId(userId)
                .orElse(null);
            if (settings == null) {
                return; // Silently ignore if settings don't exist yet
            }
            
            settings.recordCcContactUsage(normalized);
            rfiSettingsRepository.save(settings);
            log.debug("Recorded CC contact usage for user: {}, email: {}", userId, normalized);
        } catch (Exception e) {
            log.debug("Failed to record CC contact usage", e);
        }
    }
    
    /**
     * Record email usage for a specific type
     */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordContactUsage(Long userId, RfiEmailAddressType type, String email) {
        if (type == RfiEmailAddressType.TO) {
            recordToContactUsage(userId, email);
        } else {
            recordCcContactUsage(userId, email);
        }
    }
    
    /**
     * Manually add or update a TO contact
     */
    @Transactional
    public List<EmailContactDto> addOrUpdateToContact(Long userId, String email) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        
        RfiSettings settings = getOrCreateRfiSettings(userId);
        settings.addOrUpdateToContact(normalized, false);
        rfiSettingsRepository.save(settings);
        
        log.info("Added TO contact for user: {}, email: {}", userId, normalized);
        return getAllToContacts(userId);
    }
    
    /**
     * Manually add or update a CC contact
     */
    @Transactional
    public List<EmailContactDto> addOrUpdateCcContact(Long userId, String email) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        
        RfiSettings settings = getOrCreateRfiSettings(userId);
        settings.addOrUpdateCcContact(normalized, false);
        rfiSettingsRepository.save(settings);
        
        log.info("Added CC contact for user: {}, email: {}", userId, normalized);
        return getAllCcContacts(userId);
    }
    
    /**
     * Add or update contact of specific type
     */
    @Transactional
    public List<EmailContactDto> addOrUpdateContact(Long userId, RfiEmailAddressType type, String email) {
        if (type == RfiEmailAddressType.TO) {
            return addOrUpdateToContact(userId, email);
        } else {
            return addOrUpdateCcContact(userId, email);
        }
    }
    
    /**
     * Set favorite status for a TO contact
     */
    @Transactional
    public List<EmailContactDto> setToContactFavorite(Long userId, String email, boolean favorite) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        
        RfiSettings settings = getOrCreateRfiSettings(userId);
        settings.setToContactFavorite(normalized, favorite);
        rfiSettingsRepository.save(settings);
        
        log.info("Set TO contact favorite for user: {}, email: {}, favorite: {}", userId, normalized, favorite);
        return getAllToContacts(userId);
    }
    
    /**
     * Set favorite status for a CC contact
     */
    @Transactional
    public List<EmailContactDto> setCcContactFavorite(Long userId, String email, boolean favorite) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        
        RfiSettings settings = getOrCreateRfiSettings(userId);
        settings.setCcContactFavorite(normalized, favorite);
        rfiSettingsRepository.save(settings);
        
        log.info("Set CC contact favorite for user: {}, email: {}, favorite: {}", userId, normalized, favorite);
        return getAllCcContacts(userId);
    }
    
    /**
     * Set favorite status for contact of specific type
     */
    @Transactional
    public List<EmailContactDto> setContactFavorite(Long userId, RfiEmailAddressType type, String email, boolean favorite) {
        if (type == RfiEmailAddressType.TO) {
            return setToContactFavorite(userId, email, favorite);
        } else {
            return setCcContactFavorite(userId, email, favorite);
        }
    }
    
    /**
     * Delete a TO contact
     */
    @Transactional
    public List<EmailContactDto> deleteToContact(Long userId, String email) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        
        RfiSettings settings = getOrCreateRfiSettings(userId);
        settings.removeToContact(normalized);
        rfiSettingsRepository.save(settings);
        
        log.info("Deleted TO contact for user: {}, email: {}", userId, normalized);
        return getAllToContacts(userId);
    }
    
    /**
     * Delete a CC contact
     */
    @Transactional
    public List<EmailContactDto> deleteCcContact(Long userId, String email) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        
        RfiSettings settings = getOrCreateRfiSettings(userId);
        settings.removeCcContact(normalized);
        rfiSettingsRepository.save(settings);
        
        log.info("Deleted CC contact for user: {}, email: {}", userId, normalized);
        return getAllCcContacts(userId);
    }
    
    /**
     * Delete contact of specific type
     */
    @Transactional
    public List<EmailContactDto> deleteContact(Long userId, RfiEmailAddressType type, String email) {
        if (type == RfiEmailAddressType.TO) {
            return deleteToContact(userId, email);
        } else {
            return deleteCcContact(userId, email);
        }
    }
    
    // ==================== Encryption Methods ====================
    
    private String encryptToken(String token) {
        try {
            SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY.getBytes(), 0, ENCRYPTION_KEY.getBytes().length, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encryptedBytes = cipher.doFinal(token.getBytes());
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt token: " + e.getMessage());
        }
    }
    
    private String decryptToken(String encryptedToken) {
        try {
            SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY.getBytes(), 0, ENCRYPTION_KEY.getBytes().length, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedToken);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Failed to decrypt token: " + e.getMessage());
        }
    }
    
    // ==================== Validation & Helper Methods ====================
    
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
    
    private boolean isValidEmail(String email) {
        return email != null && !email.isEmpty() && EMAIL_PATTERN.matcher(email).matches();
    }
    
    private void validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email address is required");
        }
        if (!isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "\"" + email + "\" is not a valid email address");
        }
    }
    
    private RfiSettings getRfiSettingsOrThrow(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return rfiSettingsRepository.findByUserId(userId)
            .orElseThrow(() -> new ResourceNotFoundException("RFI settings not found for user: " + userId));
    }

    /**
     * Get the user's RfiSettings row, creating an empty one if it doesn't
     * exist yet. Email contacts (TO/CC) must work even for a user who has
     * never configured Gmail, so contact read/write methods use this
     * instead of getRfiSettingsOrThrow, which would otherwise 400 on every
     * /contacts/** call until the user first saves Gmail settings.
     */
    @Transactional
    private RfiSettings getOrCreateRfiSettings(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return rfiSettingsRepository.findByUserId(userId)
            .orElseGet(() -> {
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
                RfiSettings newSettings = new RfiSettings();
                newSettings.setUser(user);
                // gmail_email is NOT NULL in the schema; default to empty
                // string until the user actually configures Gmail via
                // POST /api/rfi-settings.
                newSettings.setGmailEmail("");
                return rfiSettingsRepository.save(newSettings);
            });
    }
    
    // ==================== Mapping Methods ====================
    
    private RfiSettingsDto mapToDto(RfiSettings settings) {
        return RfiSettingsDto.builder()
            .id(settings.getId())
            .gmailEmail(settings.getGmailEmail())
            .gmailClientId(settings.getGmailClientId())
            .gmailClientSecret(settings.getGmailClientSecret()) // encrypted; controller scrubs before responding
            .gmailRefreshToken(settings.getGmailRefreshToken())
            .tokenValid(settings.getTokenValid())
            .tokenExpiryDate(settings.getTokenExpiryDate())
            .toContacts(settings.getAllToContacts().stream()
                .map(c -> EmailContactDto.fromEntity(c, RfiEmailAddressType.TO))
                .collect(Collectors.toList()))
            .ccContacts(settings.getAllCcContacts().stream()
                .map(c -> EmailContactDto.fromEntity(c, RfiEmailAddressType.CC))
                .collect(Collectors.toList()))
            .build();
    }
}