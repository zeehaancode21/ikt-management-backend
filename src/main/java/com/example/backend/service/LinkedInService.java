package com.example.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

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
     * Post content to LinkedIn with provided token (no image attachment).
     * Kept for backward compatibility with any other callers.
     */
    public String postContent(String content, String topic, String providedToken) throws Exception {
        return postContent(content, topic, providedToken, null);
    }

    /**
     * Post content to LinkedIn with provided token, optionally attaching an
     * image. {@code imageBase64} is expected as a base64 data URL
     * (e.g. "data:image/png;base64,....") or a bare base64 string — both are
     * handled. When null/blank, behaves exactly like a text-only post.
     */
    public String postContent(String content, String topic, String providedToken, String imageBase64) throws Exception {
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

        // If the caller attached an image, upload it to LinkedIn first so we
        // have an asset URN to reference from the post body below. Any
        // failure here is surfaced clearly rather than silently dropping
        // the image and posting text-only.
        String assetUrn = null;
        if (imageBase64 != null && !imageBase64.isBlank()) {
            assetUrn = uploadImageAsset(token, personUrn, imageBase64);
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

        if (assetUrn != null) {
            shareContent.put("shareMediaCategory", "IMAGE");
            com.fasterxml.jackson.databind.node.ArrayNode mediaArray = objectMapper.createArrayNode();
            ObjectNode mediaItem = objectMapper.createObjectNode();
            mediaItem.put("status", "READY");
            mediaItem.put("media", assetUrn);
            ObjectNode mediaTitle = objectMapper.createObjectNode();
            mediaTitle.put("text", topic != null && !topic.isBlank() ? topic : "Image");
            mediaItem.set("title", mediaTitle);
            mediaArray.add(mediaItem);
            shareContent.set("media", mediaArray);
        } else {
            shareContent.put("shareMediaCategory", "NONE");
        }

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
     * Uploads an image to LinkedIn's asset store and returns the resulting
     * asset URN (e.g. "urn:li:digitalmediaAsset:xxxx") so it can be
     * referenced from a UGC post. Implements LinkedIn's two-step
     * register-then-upload flow:
     *   1) POST /v2/assets?action=registerUpload  -> upload URL + asset URN
     *   2) PUT the raw image bytes to that upload URL
     */
    private String uploadImageAsset(String token, String personUrn, String imageBase64) throws Exception {
        // Strip a data-URL prefix like "data:image/png;base64," if present.
        String rawBase64 = imageBase64;
        int commaIdx = imageBase64.indexOf(',');
        if (imageBase64.startsWith("data:") && commaIdx != -1) {
            rawBase64 = imageBase64.substring(commaIdx + 1);
        }

        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(rawBase64);
        } catch (IllegalArgumentException e) {
            throw new Exception("Attached image could not be decoded: " + e.getMessage());
        }

        // Step 1: register the upload
        ObjectNode registerBody = objectMapper.createObjectNode();
        ObjectNode registerUploadRequest = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode recipes = objectMapper.createArrayNode();
        recipes.add("urn:li:digitalmediaRecipe:feedshare-image");
        registerUploadRequest.set("recipes", recipes);
        registerUploadRequest.put("owner", personUrn);
        com.fasterxml.jackson.databind.node.ArrayNode relationships = objectMapper.createArrayNode();
        ObjectNode relationship = objectMapper.createObjectNode();
        relationship.put("relationshipType", "OWNER");
        relationship.put("identifier", "urn:li:userGeneratedContent");
        relationships.add(relationship);
        registerUploadRequest.set("serviceRelationships", relationships);
        registerBody.set("registerUploadRequest", registerUploadRequest);

        HttpHeaders registerHeaders = new HttpHeaders();
        registerHeaders.setContentType(MediaType.APPLICATION_JSON);
        registerHeaders.setBearerAuth(token);
        registerHeaders.add("X-Restli-Protocol-Version", "2.0.0");

        ResponseEntity<String> registerResponse;
        try {
            registerResponse = restTemplate.exchange(
                "https://api.linkedin.com/v2/assets?action=registerUpload",
                HttpMethod.POST,
                new HttpEntity<>(registerBody.toString(), registerHeaders),
                String.class
            );
        } catch (Exception e) {
            throw new Exception("Failed to register image upload with LinkedIn: " + e.getMessage());
        }

        if (!registerResponse.getStatusCode().is2xxSuccessful() || registerResponse.getBody() == null) {
            throw new Exception("LinkedIn image upload registration failed: "
                + registerResponse.getStatusCode() + " - " + registerResponse.getBody());
        }

        JsonNode registerJson = objectMapper.readTree(registerResponse.getBody());
        JsonNode value = registerJson.path("value");
        String assetUrn = value.path("asset").asText(null);
        String uploadUrl = value
            .path("uploadMechanism")
            .path("com.linkedin.digitalmedia.uploading.MediaUploadHttpRequest")
            .path("uploadUrl")
            .asText(null);

        if (assetUrn == null || uploadUrl == null) {
            throw new Exception("LinkedIn did not return an upload URL/asset for the image.");
        }

        // Step 2: upload the actual image bytes to the returned upload URL
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setBearerAuth(token);
        uploadHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<byte[]> uploadEntity = new HttpEntity<>(imageBytes, uploadHeaders);

        ResponseEntity<byte[]> uploadResponse;
        try {
            uploadResponse = restTemplate.exchange(
                uploadUrl,
                HttpMethod.PUT,
                uploadEntity,
                byte[].class
            );
        } catch (Exception e) {
            throw new Exception("Failed to upload image bytes to LinkedIn: " + e.getMessage());
        }

        if (!uploadResponse.getStatusCode().is2xxSuccessful()) {
            throw new Exception("LinkedIn image binary upload failed: " + uploadResponse.getStatusCode());
        }

        return assetUrn;
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