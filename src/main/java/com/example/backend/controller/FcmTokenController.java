package com.example.backend.controller;

import com.example.backend.service.FcmService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
// @CrossOrigin(
//     origins = "http://localhost:5173",
//     allowCredentials = "true",
//     allowedHeaders = "*",
//     methods = {
//         RequestMethod.GET, RequestMethod.POST,
//         RequestMethod.PUT, RequestMethod.DELETE,
//         RequestMethod.OPTIONS
//     }
// )
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