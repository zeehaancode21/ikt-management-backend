package com.example.backend.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.backend.service.FcmService;

@RestController
@RequestMapping("/api/notifications")
public class FcmTokenController {

    private final FcmService fcmService;

    public FcmTokenController(FcmService fcmService) {
        this.fcmService = fcmService;
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<?> registerToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body) {
        try {
            String token = body.get("token");

            if (token == null || token.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Token is required"
                ));
            }

            // Handle anonymous users for testing
            String username = userDetails != null ? userDetails.getUsername() : "anonymous";
            
            fcmService.saveToken(username, token);
            
            return ResponseEntity.ok(Map.of(
                "message", "FCM token registered successfully",
                "username", username
            ));
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to register FCM token: " + e.getMessage()
            ));
        }
    }

    @DeleteMapping("/fcm-token")
    public ResponseEntity<?> removeToken(@RequestBody Map<String, String> body) {
        try {
            String token = body.get("token");
            if (token != null) {
                fcmService.deleteToken(token);
            }
            return ResponseEntity.ok(Map.of("message", "FCM token removed"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to remove FCM token: " + e.getMessage()
            ));
        }
    }
}