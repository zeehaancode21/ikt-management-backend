// MistralService.java
package com.yourcompany.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;

@Service
public class MistralService {

    @Value("${mistral.api.key}")
    private String apiKey;

    @Value("${mistral.api.url:https://api.mistral.ai/v1/chat/completions}")
    private String apiUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MistralService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate a LinkedIn post using Mistral AI
     */
    public String generatePost(String topic, String categoryId) throws Exception {
        // Build the prompt based on category
        String prompt = buildPrompt(topic, categoryId);
        
        // Create request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "mistral-small-latest");
        requestBody.put("temperature", 0.7);
        
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        
        requestBody.put("messages", new Map[]{message});

        // Create HTTP headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // Make API call
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.exchange(
            apiUrl,
            HttpMethod.POST,
            entity,
            String.class
        );

        if (response.getStatusCode() == HttpStatus.OK) {
            return extractContentFromResponse(response.getBody());
        } else {
            throw new Exception("Mistral API error: " + response.getStatusCode());
        }
    }

    /**
     * Build a detailed prompt based on topic and category
     */
    private String buildPrompt(String topic, String categoryId) {
        String categoryContext = getCategoryContext(categoryId);
        
        return String.format(
            "You are a professional LinkedIn content creator specializing in structural steel detailing and construction.\n\n" +
            "CONTEXT: %s\n\n" +
            "TOPIC: %s\n\n" +
            "REQUIREMENTS:\n" +
            "- Write a compelling, engaging LinkedIn post (under 150 words)\n" +
            "- Professional yet conversational tone\n" +
            "- Include specific steel detailing industry insights\n" +
            "- Maximum 3 relevant hashtags\n" +
            "- No markdown or special formatting\n" +
            "- Add value with actionable insights\n" +
            "- End with a question or call-to-action to encourage engagement\n\n" +
            "Write the post directly (no preamble, no meta-commentary):",
            categoryContext,
            topic
        );
    }

    /**
     * Get context based on category
     */
    private String getCategoryContext(String categoryId) {
        Map<String, String> categoryContexts = new HashMap<>();
        categoryContexts.put("showcase", 
            "You're showcasing a steel detailing project. Focus on challenges, solutions, and results. " +
            "Highlight precision, technical expertise, and successful outcomes.");
        
        categoryContexts.put("insights", 
            "You're sharing industry insights about steel detailing. Focus on education, best practices, " +
            "and the importance of accurate detailing in construction.");
        
        categoryContexts.put("technical", 
            "You're discussing technical aspects of steel detailing. Focus on specific processes, " +
            "tools like Tekla, BIM, clash detection, and fabrication efficiency.");
        
        categoryContexts.put("branding", 
            "You're promoting your steel detailing company. Focus on expertise, quality commitment, " +
            "team experience, and company values in the construction industry.");
        
        categoryContexts.put("educational", 
            "You're educating about steel detailing fundamentals. Focus on explaining concepts, " +
            "processes, and the value of professional detailing services.");
        
        categoryContexts.put("client", 
            "You're speaking to clients and fabricators. Focus on benefits, cost savings, " +
            "time efficiency, and how detailing services support their success.");
        
        categoryContexts.put("engagement", 
            "You're engaging with the community. Focus on asking questions, sharing experiences, " +
            "and encouraging discussion about steel detailing challenges and trends.");
        
        categoryContexts.put("aiprompts", 
            "You're creating visual content prompts. Focus on describing images and scenes " +
            "that showcase steel detailing work, models, and construction progress.");
        
        categoryContexts.put("hooks", 
            "You're creating attention-grabbing posts. Focus on powerful statements, " +
            "memorable phrases, and engaging hooks that drive engagement.");
        
        return categoryContexts.getOrDefault(categoryId, 
            "You're creating content about steel detailing in construction.");
    }

    /**
     * Extract content from Mistral API response
     */
    private String extractContentFromResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        
        if (choices != null && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                String content = message.get("content").asText();
                // Clean up the content
                content = content.replaceAll("^[\"\'\n\r]+|[\"\'\n\r]+$", "");
                return content.trim();
            }
        }
        
        throw new Exception("Could not extract content from Mistral response");
    }

    /**
     * Generate post with streaming support for real-time updates
     */
    public String generatePostStreaming(String topic, String categoryId, 
                                       java.util.function.Consumer<String> onChunk) throws Exception {
        String prompt = buildPrompt(topic, categoryId);
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "mistral-small-latest");
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);
        
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        
        requestBody.put("messages", new Map[]{message});

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        // Use RestTemplate with response streaming
        // This is a simplified version - for production, use WebClient or similar
        ResponseEntity<String> response = restTemplate.exchange(
            apiUrl,
            HttpMethod.POST,
            entity,
            String.class
        );

        if (response.getStatusCode() == HttpStatus.OK) {
            // Parse streaming response
            String fullContent = extractContentFromResponse(response.getBody());
            if (onChunk != null) {
                onChunk.accept(fullContent);
            }
            return fullContent;
        } else {
            throw new Exception("Mistral API error: " + response.getStatusCode());
        }
    }
}