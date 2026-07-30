package com.example.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class LinkedInService {

    @Value("${linkedin.access.token:}")
    private String defaultAccessToken;

    @Value("${linkedin.person.urn:}")
    private String defaultPersonUrn;

    @Value("${linkedin.api.url:https://api.linkedin.com/v2/ugcPosts}")
    private String apiUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LinkedInService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Post content to LinkedIn with provided token
     */
    public String postContent(String content, String topic, String providedToken) throws Exception {
        // Use provided token or fallback to default
        String token = (providedToken != null && !providedToken.isEmpty()) 
            ? providedToken 
            : defaultAccessToken;
        
        String personUrn = defaultPersonUrn;

        // Validate inputs
        if (token == null || token.isEmpty()) {
            throw new Exception("LinkedIn access token is required. Please authenticate with LinkedIn.");
        }
        
        if (personUrn == null || personUrn.isEmpty()) {
            throw new Exception("LinkedIn person URN is not configured.");
        }

        // Escape content for JSON
        String escapedContent = escapeJson(content);

        // Build request body
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("author", personUrn);
        requestBody.put("lifecycleState", "PUBLISHED");
        
        ObjectNode specificContent = objectMapper.createObjectNode();
        ObjectNode shareContent = objectMapper.createObjectNode();
        ObjectNode shareCommentary = objectMapper.createObjectNode();
        shareCommentary.put("text", escapedContent);
        shareContent.set("shareCommentary", shareCommentary);
        shareContent.put("shareMediaCategory", "NONE");
        specificContent.set("com.linkedin.ugc.ShareContent", shareContent);
        requestBody.set("specificContent", specificContent);
        
        ObjectNode visibility = objectMapper.createObjectNode();
        visibility.put("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC");
        requestBody.set("visibility", visibility);

        // Create HTTP headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.add("X-Restli-Protocol-Version", "2.0.0");

        try {
            // Make API call
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                String.class
            );

            // Log the post
            logPost(content, topic, response.getStatusCode().value(), response.getBody());

            if (response.getStatusCode() == HttpStatus.CREATED) {
                return "Post published successfully! 🎉";
            } else if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new Exception("401 - LinkedIn access token has expired. Please re-authenticate.");
            } else if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new Exception("403 - Token lacks required permissions. Need: w_member_social");
            } else {
                String errorMsg = response.getBody();
                if (errorMsg != null && errorMsg.contains("INVALID_ACCESS_TOKEN")) {
                    throw new Exception("401 - Invalid LinkedIn token. Please re-authenticate.");
                }
                throw new Exception("LinkedIn API error: " + response.getStatusCode() + " - " + errorMsg);
            }
        } catch (Exception e) {
            // Check if it's an authentication error
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("401") || 
                errorMsg.contains("UNAUTHORIZED") || 
                errorMsg.contains("INVALID_ACCESS_TOKEN"))) {
                throw new Exception("401 - LinkedIn authentication failed. Please re-authenticate your account.");
            }
            throw e;
        }
    }

    /**
     * Validate LinkedIn access token
     */
    public boolean validateToken(String token) throws Exception {
        String tokenToValidate = (token != null && !token.isEmpty()) ? token : defaultAccessToken;
        
        if (tokenToValidate == null || tokenToValidate.isEmpty()) {
            return false;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenToValidate);
            headers.add("X-Restli-Protocol-Version", "2.0.0");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                "https://api.linkedin.com/v2/userinfo",
                HttpMethod.GET,
                entity,
                String.class
            );
            
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Escape special characters for JSON
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "")
                   .replace("\t", "\\t");
    }

    /**
     * Log post activity
     */
    private void logPost(String content, String topic, int statusCode, String responseBody) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logEntry = String.format(
            "[%s] Status: %d | Topic: %s | Response: %s | Content preview: %s\n",
            timestamp,
            statusCode,
            topic,
            responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 200)) : "null",
            content.substring(0, Math.min(content.length(), 100))
        );
        
        try {
            java.nio.file.Files.write(
                java.nio.file.Path.of("linkedin_post_log.txt"),
                logEntry.getBytes(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }
}