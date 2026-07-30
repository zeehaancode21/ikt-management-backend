package com.example.backend.controller;

import com.example.backend.service.LinkedInService;
import com.example.backend.service.MistralService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/social-post")
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:8080"}, allowCredentials = "true")
public class SocialHubController {

    @Autowired
    private MistralService mistralService;
    
    @Autowired
    private LinkedInService linkedInService;

    @PostMapping("/generate-post")
    public Map<String, Object> generatePost(@RequestBody Map<String, String> request) {
        String topic = request.get("topic");
        String categoryId = request.get("categoryId");
        
        try {
            String content = mistralService.generatePost(topic, categoryId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("content", content);
            response.put("topic", topic);
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    /**
     * Generate more AI topic suggestions for a category, using the same
     * Mistral token/config as post generation. The frontend sends along the
     * topics it already has (built-in + previously loaded AI topics) so the
     * new batch doesn't repeat them; the returned topics get appended to the
     * same topic list/column in the UI and behave exactly like any other
     * topic once selected.
     */
    @PostMapping("/generate-topics")
    public Map<String, Object> generateMoreTopics(@RequestBody Map<String, Object> request) {
        String categoryId = request.get("categoryId") != null ? request.get("categoryId").toString() : null;

        List<String> existingTopics = new ArrayList<>();
        Object existingRaw = request.get("existingTopics");
        if (existingRaw instanceof List<?>) {
            for (Object item : (List<?>) existingRaw) {
                if (item != null) {
                    existingTopics.add(item.toString());
                }
            }
        }

        try {
            List<String> topics = mistralService.generateMoreTopics(categoryId, existingTopics);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("topics", topics);
            response.put("categoryId", categoryId);
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @PostMapping("/post-to-linkedin")
    public Map<String, Object> postToLinkedIn(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String topic = request.get("topic");
        String linkedInToken = request.get("linkedInToken");
        
        try {
            String result = linkedInService.postContent(content, topic, linkedInToken);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", result);
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            String errorMsg = e.getMessage();
            response.put("success", false);
            response.put("message", errorMsg);
            
            // Check if it's an authentication error
            if (errorMsg != null && (errorMsg.contains("401") || 
                errorMsg.contains("UNAUTHORIZED") || 
                errorMsg.contains("token") || 
                errorMsg.contains("authenticate"))) {
                response.put("needsAuth", true);
                response.put("error", "LINKEDIN_AUTH_ERROR");
            }
            
            return response;
        }
    }

    @PostMapping("/linkedin/validate-token")
    public Map<String, Object> validateLinkedInToken(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        
        try {
            boolean isValid = linkedInService.validateToken(token);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("valid", isValid);
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }
}