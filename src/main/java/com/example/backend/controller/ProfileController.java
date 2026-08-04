package com.example.backend.controller;

import com.example.backend.entity.EmployeeProfile;
import com.example.backend.entity.User;
import com.example.backend.repository.EmployeeProfileRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.LeavePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Basic employee profile (name, DOB, mobile, address) and profile picture.
 * Field reads/writes are restricted to the employee themself or the owner.
 * The picture endpoint is open to ANY authenticated user, by design — it
 * needs to render in Messages, sidebars, etc. wherever a colleague's name
 * appears, the same way profile photos work in any normal workplace app.
 */
@RestController
@RequestMapping("/profile")
// @CrossOrigin(
//     origins = "http://localhost:5173",
//     allowCredentials = "true",
//     allowedHeaders = "*"
// )
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    @Autowired private EmployeeProfileRepository profileRepo;
    @Autowired private UserRepository userRepo;

    private static final long MAX_PICTURE_BYTES = 5L * 1024 * 1024; // 5MB

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private boolean isOwner(String username) {
        return userRepo.findByUsername(username)
            .map(u -> "OWNER".equalsIgnoreCase(u.getRole()))
            .orElse(false);
    }

    private void requireOwnerOrSelf(String targetUsername) {
        String actor = currentUsername();
        if (!actor.equals(targetUsername) && !isOwner(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to access this profile");
        }
    }

    public record ProfileRequest(
        String fullName, 
        String email, 
        LocalDate dateOfBirth, 
        LocalDate dateOfJoining,
        String mobileNo, 
        String currentAddress
    ) {}

    // ---------- Helper Methods ----------
    
    // ADD THIS METHOD - Email validation helper
    private boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return true; // Allow null/empty, handled separately
        }
        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        return email.matches(emailRegex);
    }

    // ---------- Read ----------

    @GetMapping("/me")
    public Map<String, Object> getOwnProfile() {
        return toResponse(currentUsername());
    }

    @GetMapping("/{username}")
    public Map<String, Object> getProfile(@PathVariable String username) {
        requireOwnerOrSelf(username);
        return toResponse(username);
    }

    private Map<String, Object> toResponse(String username) {
        EmployeeProfile profile = profileRepo.findByUsername(username).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("fullName", profile != null ? profile.getFullName() : null);
        result.put("email", profile != null ? profile.getEmail() : null); // ADD THIS LINE
        result.put("dateOfBirth", profile != null ? profile.getDateOfBirth() : null);
        LocalDate dateOfJoining = profile != null ? profile.getDateOfJoining() : null;
        result.put("dateOfJoining", dateOfJoining);
        result.put("mobileNo", profile != null ? profile.getMobileNo() : null);
        result.put("currentAddress", profile != null ? profile.getCurrentAddress() : null);
        // Custom display title (e.g. "Senior Checker") set by an admin via
        // Employee Management. Null/blank means the caller should fall back
        // to displaying the system role instead.
        result.put("roleName", profile != null ? profile.getRoleName() : null);
        result.put("hasProfilePicture", profile != null && profile.getProfilePicture() != null);
        // Annual leave entitlement, derived from date of joining: 24 days
        // once the employee has completed 3 years of service, 18 before that.
        result.put("leaveLimit", LeavePolicy.leaveLimitFor(dateOfJoining));
        return result;
    }

    // ---------- Write ----------

    @PutMapping("/me")
    @Transactional
    public Map<String, String> updateOwnProfile(@RequestBody ProfileRequest req) {
        return saveProfile(currentUsername(), req);
    }

    @PutMapping("/{username}")
    @Transactional
    public Map<String, String> updateProfileFor(@PathVariable String username, @RequestBody ProfileRequest req) {
        requireOwnerOrSelf(username);
        return saveProfile(username, req);
    }

    private Map<String, String> saveProfile(String username, ProfileRequest req) {
        // Email validation
        if (req.email() != null && !req.email().isBlank() && !isValidEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email format");
        }
        
        // Mobile validation - ONLY validate if provided (not empty/blank)
        if (req.mobileNo() != null && !req.mobileNo().isBlank()) {
            if (!req.mobileNo().matches("\\+?\\d{10,15}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Mobile number should be 10–15 digits, optionally starting with +");
            }
        }
        
        if (req.dateOfBirth() != null && req.dateOfBirth().isAfter(LocalDate.now().minusYears(15))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date of birth doesn't look right");
        }

        // Date of joining validation - can't be in the future, and can't be
        // before the employee's date of birth (if both are set).
        if (req.dateOfJoining() != null) {
            if (req.dateOfJoining().isAfter(LocalDate.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date of joining can't be in the future");
            }
            if (req.dateOfBirth() != null && req.dateOfJoining().isBefore(req.dateOfBirth())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date of joining can't be before date of birth");
            }
        }

        EmployeeProfile profile = profileRepo.findByUsername(username).orElseGet(EmployeeProfile::new);
        profile.setUsername(username);
        profile.setFullName(req.fullName());
        profile.setEmail(req.email()); // ADD THIS LINE
        profile.setDateOfBirth(req.dateOfBirth());
        profile.setDateOfJoining(req.dateOfJoining());
        profile.setMobileNo(req.mobileNo());
        profile.setCurrentAddress(req.currentAddress());
        profile.setUpdatedBy(currentUsername());
        profileRepo.save(profile);

        // Keep users.email in sync with employee_profiles.email.
        // Root cause of the original bug: this method only ever wrote the
        // email to EmployeeProfile — nothing in the /profile/me flow ever
        // touched the users table, so users.email was left stale (or NULL
        // for accounts created before email was collected at registration).
        // Runs inside the same @Transactional request (see updateOwnProfile
        // / updateProfileFor) so both tables commit or roll back together.
        syncUserEmail(username, req.email());

        return Map.of("message", "Profile updated");
    }

    /**
     * Mirrors the employee_profiles.email value into users.email so the two
     * stay consistent without a manual SQL patch. Handles all three cases:
     * users.email was NULL/empty, users.email already had a value that
     * differs from the new one, and no-op when they already match.
     */
    private void syncUserEmail(String username, String newEmail) {
        // Nothing to sync if the profile email itself is blank — don't
        // overwrite a real users.email with an empty value from a partial
        // profile update.
        if (newEmail == null || newEmail.isBlank()) {
            log.debug("Skipping users.email sync for '{}': profile email is empty", username);
            return;
        }

        User user = userRepo.findByUsername(username).orElse(null);
        if (user == null) {
            // Should not normally happen (a profile implies a user account),
            // but don't let a data inconsistency elsewhere blow up the
            // profile save.
            log.warn("Could not sync email for '{}': no matching users row found", username);
            return;
        }

        String currentEmail = user.getEmail();
        if (newEmail.equalsIgnoreCase(currentEmail)) {
            return; // already in sync, nothing to do
        }

        boolean wasMissing = (currentEmail == null || currentEmail.isBlank());
        user.setEmail(newEmail);
        userRepo.save(user);

    }

    // ---------- Profile picture ----------

    @PostMapping("/me/picture")
    public Map<String, String> uploadOwnPicture(@RequestParam("file") MultipartFile file) {
        return savePicture(currentUsername(), file);
    }

    @PostMapping("/{username}/picture")
    public Map<String, String> uploadPictureFor(@PathVariable String username, @RequestParam("file") MultipartFile file) {
        requireOwnerOrSelf(username);
        return savePicture(username, file);
    }

    private Map<String, String> savePicture(String username, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file provided");
        }
        if (file.getSize() > MAX_PICTURE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image must be 5MB or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image");
        }

        try {
            EmployeeProfile profile = profileRepo.findByUsername(username).orElseGet(EmployeeProfile::new);
            profile.setUsername(username);
            profile.setProfilePicture(file.getBytes());
            profile.setProfilePictureContentType(contentType);
            profile.setUpdatedBy(currentUsername());
            profileRepo.save(profile);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded image");
        }
        return Map.of("message", "Profile picture updated");
    }

    /** Open to any authenticated user — needed wherever a colleague's photo is shown. */
    @GetMapping("/picture/{username}")
    public ResponseEntity<byte[]> getPicture(@PathVariable String username) {
        EmployeeProfile profile = profileRepo.findByUsername(username).orElse(null);
        if (profile == null || profile.getProfilePicture() == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = profile.getProfilePictureContentType() != null
                ? MediaType.parseMediaType(profile.getProfilePictureContentType())
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(profile.getProfilePicture());
    }
}