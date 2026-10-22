package com.example.backend.controller;

import com.example.backend.dto.RfiSettingsDto;
import com.example.backend.dto.EmailContactDto;
import com.example.backend.entity.RfiEmailAddressType;
import com.example.backend.service.RfiSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RfiSettingsController.java
 * =====================================================================
 * MERGED Controller combining both RfiSettingsController and
 * RfiEmailContactController endpoints into one.
 * 
 * All operations that previously required two separate endpoints now
 * go through this unified controller, which delegates to the merged
 * RfiSettingsService.
 * =====================================================================
 */
@RestController
@RequestMapping("/api/rfi-settings")
@RequiredArgsConstructor
@Slf4j
public class RfiSettingsController {
    
    @Autowired 
    private RfiSettingsService rfiSettingsService;
    
    @Autowired
    private com.example.backend.repository.UserRepository userRepository;

    /**
     * Get current user ID from authentication context.
     * JwtFilter sets the principal to a Spring Security UserDetails (with
     * the username), never a raw Long id — mirrors
     * RfiEmailContactController#resolveCurrentUserId.
     */
    private Long getCurrentUserId() {
        org.springframework.security.core.Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "No user found for: " + authentication.getName()))
                .getId();
    }
    
    // ==================== Settings Management Endpoints ====================
    
    /**
     * GET /api/rfi-settings
     * Retrieve current RFI settings for the authenticated user, including all email contacts
     */
    @GetMapping
    public ResponseEntity<RfiSettingsDto> getSettings() {
        Long userId = getCurrentUserId();
        log.info("Fetching RFI settings for user: {}", userId);
        RfiSettingsDto settings = rfiSettingsService.getSettingsByUserId(userId);
        return ResponseEntity.ok(settings);
    }
    
    /**
     * POST /api/rfi-settings
     * Save or update RFI settings (Gmail configuration)
     * Email contacts are not updated through this endpoint
     */
    @PostMapping
    public ResponseEntity<RfiSettingsDto> saveSettings(@RequestBody RfiSettingsDto settingsDto) {
        Long userId = getCurrentUserId();
        log.info("Saving RFI settings for user: {}", userId);
        RfiSettingsDto saved = rfiSettingsService.saveSettings(userId, settingsDto);
        return ResponseEntity.ok(saved);
    }
    
    /**
     * DELETE /api/rfi-settings/token
     * Clear the Gmail refresh token (disconnect Gmail)
     */
    @DeleteMapping("/token")
    public ResponseEntity<Map<String, String>> clearToken() {
        Long userId = getCurrentUserId();
        log.info("Clearing token for user: {}", userId);
        rfiSettingsService.clearToken(userId);
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Token cleared successfully");
        return ResponseEntity.ok(response);
    }
    
    /**
     * POST /api/rfi-settings/validate-token
     * Validate if the current token is still valid
     */
    @PostMapping("/validate-token")
    public ResponseEntity<Map<String, Boolean>> validateToken() {
        Long userId = getCurrentUserId();
        try {
            boolean hasValid = rfiSettingsService.hasValidSettings(userId);
            Map<String, Boolean> response = new HashMap<>();
            response.put("valid", hasValid);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Boolean> response = new HashMap<>();
            response.put("valid", false);
            return ResponseEntity.ok(response);
        }
    }
    
    // ==================== Email Contact Endpoints - TO Recipients ====================
    
    /**
     * GET /api/rfi-settings/contacts/to
     * Get all TO contacts (both favorited and unfavorited)
     */
    @GetMapping("/contacts/to")
    public ResponseEntity<List<EmailContactDto>> getAllToContacts() {
        Long userId = getCurrentUserId();
        log.info("Fetching all TO contacts for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.getAllToContacts(userId);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * GET /api/rfi-settings/contacts/to/favorites
     * Get only favorited TO contacts (for dropdown on Create RFI form)
     */
    @GetMapping("/contacts/to/favorites")
    public ResponseEntity<List<EmailContactDto>> getFavoriteToContacts() {
        Long userId = getCurrentUserId();
        log.info("Fetching favorite TO contacts for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.getFavoriteToContacts(userId);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * POST /api/rfi-settings/contacts/to
     * Add a new TO contact (or update if it already exists)
     * Request body: { "email": "recipient@example.com" }
     */
    @PostMapping("/contacts/to")
    public ResponseEntity<List<EmailContactDto>> addToContact(@RequestBody Map<String, String> request) {
        Long userId = getCurrentUserId();
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        log.debug("Adding TO contact for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.addOrUpdateToContact(userId, email);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * PUT /api/rfi-settings/contacts/to/favorite
     * Set favorite status for a TO contact
     * Request body: { "email": "recipient@example.com", "favorite": true }
     */
    @PutMapping("/contacts/to/favorite")
    public ResponseEntity<List<EmailContactDto>> setToContactFavorite(@RequestBody Map<String, Object> request) {
        Long userId = getCurrentUserId();
        String email = (String) request.get("email");
        Boolean favorite = (Boolean) request.get("favorite");
        
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (favorite == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Favorite status is required");
        }
        
        log.info("Setting TO contact favorite for user: {}, favorite: {}", userId, favorite);
        List<EmailContactDto> contacts = rfiSettingsService.setToContactFavorite(userId, email, favorite);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * DELETE /api/rfi-settings/contacts/to
     * Delete a TO contact
     * Query param: email=recipient@example.com
     */
    @DeleteMapping("/contacts/to")
    public ResponseEntity<List<EmailContactDto>> deleteToContact(@RequestParam String email) {
        Long userId = getCurrentUserId();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        log.info("Deleting TO contact for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.deleteToContact(userId, email);
        return ResponseEntity.ok(contacts);
    }
    
    // ==================== Email Contact Endpoints - CC Recipients ====================
    
    /**
     * GET /api/rfi-settings/contacts/cc
     * Get all CC contacts (both favorited and unfavorited)
     */
    @GetMapping("/contacts/cc")
    public ResponseEntity<List<EmailContactDto>> getAllCcContacts() {
        Long userId = getCurrentUserId();
        log.info("Fetching all CC contacts for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.getAllCcContacts(userId);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * GET /api/rfi-settings/contacts/cc/favorites
     * Get only favorited CC contacts (for dropdown on Create RFI form)
     */
    @GetMapping("/contacts/cc/favorites")
    public ResponseEntity<List<EmailContactDto>> getFavoriteCcContacts() {
        Long userId = getCurrentUserId();
        log.info("Fetching favorite CC contacts for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.getFavoriteCcContacts(userId);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * POST /api/rfi-settings/contacts/cc
     * Add a new CC contact (or update if it already exists)
     * Request body: { "email": "cc@example.com" }
     */
    @PostMapping("/contacts/cc")
    public ResponseEntity<List<EmailContactDto>> addCcContact(@RequestBody Map<String, String> request) {
        Long userId = getCurrentUserId();
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        log.info("Adding CC contact for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.addOrUpdateCcContact(userId, email);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * PUT /api/rfi-settings/contacts/cc/favorite
     * Set favorite status for a CC contact
     * Request body: { "email": "cc@example.com", "favorite": true }
     */
    @PutMapping("/contacts/cc/favorite")
    public ResponseEntity<List<EmailContactDto>> setCcContactFavorite(@RequestBody Map<String, Object> request) {
        Long userId = getCurrentUserId();
        String email = (String) request.get("email");
        Boolean favorite = (Boolean) request.get("favorite");
        
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (favorite == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Favorite status is required");
        }
        
        log.info("Setting CC contact favorite for user: {}, favorite: {}", userId, favorite);
        List<EmailContactDto> contacts = rfiSettingsService.setCcContactFavorite(userId, email, favorite);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * DELETE /api/rfi-settings/contacts/cc
     * Delete a CC contact
     * Query param: email=cc@example.com
     */
    @DeleteMapping("/contacts/cc")
    public ResponseEntity<List<EmailContactDto>> deleteCcContact(@RequestParam String email) {
        Long userId = getCurrentUserId();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        log.info("Deleting CC contact for user: {}", userId);
        List<EmailContactDto> contacts = rfiSettingsService.deleteCcContact(userId, email);
        return ResponseEntity.ok(contacts);
    }
    
    // ==================== Email Contact Endpoints - Generic (by Type) ====================
    
    /**
     * GET /api/rfi-settings/contacts/{type}
     * Get all contacts of a specific type (TO or CC)
     * Path param: type = TO or CC
     */
    @GetMapping("/contacts/{type}")
    public ResponseEntity<List<EmailContactDto>> getAllContacts(@PathVariable String type) {
        Long userId = getCurrentUserId();
        RfiEmailAddressType addressType = parseAddressType(type);
        log.info("Fetching all {} contacts for user: {}", type, userId);
        List<EmailContactDto> contacts = rfiSettingsService.getAllContacts(userId, addressType);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * GET /api/rfi-settings/contacts/{type}/favorites
     * Get only favorited contacts of a specific type
     */
    @GetMapping("/contacts/{type}/favorites")
    public ResponseEntity<List<EmailContactDto>> getFavoriteContacts(@PathVariable String type) {
        Long userId = getCurrentUserId();
        RfiEmailAddressType addressType = parseAddressType(type);
        log.info("Fetching favorite {} contacts for user: {}", type, userId);
        List<EmailContactDto> contacts = rfiSettingsService.getFavoriteContacts(userId, addressType);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * POST /api/rfi-settings/contacts/{type}
     * Add a contact of a specific type
     */
    @PostMapping("/contacts/{type}")
    public ResponseEntity<List<EmailContactDto>> addContact(
            @PathVariable String type,
            @RequestBody Map<String, String> request) {
        Long userId = getCurrentUserId();
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        RfiEmailAddressType addressType = parseAddressType(type);
        log.debug("Adding {} contact for user: {}", type, userId);
        List<EmailContactDto> contacts = rfiSettingsService.addOrUpdateContact(userId, addressType, email);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * PUT /api/rfi-settings/contacts/{type}/favorite
     * Set favorite status for contact of specific type
     */
    @PutMapping("/contacts/{type}/favorite")
    public ResponseEntity<List<EmailContactDto>> setContactFavorite(
            @PathVariable String type,
            @RequestBody Map<String, Object> request) {
        Long userId = getCurrentUserId();
        String email = (String) request.get("email");
        Boolean favorite = (Boolean) request.get("favorite");
        
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (favorite == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Favorite status is required");
        }
        
        RfiEmailAddressType addressType = parseAddressType(type);
        log.info("Setting {} contact favorite for user: {}, favorite: {}", type, userId, favorite);
        List<EmailContactDto> contacts = rfiSettingsService.setContactFavorite(userId, addressType, email, favorite);
        return ResponseEntity.ok(contacts);
    }
    
    /**
     * DELETE /api/rfi-settings/contacts/{type}
     * Delete a contact of specific type
     */
    @DeleteMapping("/contacts/{type}")
    public ResponseEntity<List<EmailContactDto>> deleteContact(
            @PathVariable String type,
            @RequestParam String email) {
        Long userId = getCurrentUserId();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        RfiEmailAddressType addressType = parseAddressType(type);
        log.info("Deleting {} contact for user: {}", type, userId);
        List<EmailContactDto> contacts = rfiSettingsService.deleteContact(userId, addressType, email);
        return ResponseEntity.ok(contacts);
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Parse address type from string (case-insensitive)
     */
    private RfiEmailAddressType parseAddressType(String type) {
        try {
            return RfiEmailAddressType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                "Invalid type. Must be TO or CC");
        }
    }
}