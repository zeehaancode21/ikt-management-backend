package com.example.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MistralService {

    // Fixed company branding — always used verbatim, never asked for again,
    // never left as a placeholder. See buildPrompt()/sanitizePost().
    private static final String COMPANY_NAME = "IK Tangience";
    private static final String COMPANY_TAGLINE = "We Speak Fluent Steel";

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
        // Validate API key
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("Mistral API key is not configured");
        }

        // Build the prompt based on category
        String prompt = buildPrompt(topic, categoryId);
        
        // Create request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "mistral-small-latest");
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 300);
        
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        
        requestBody.put("messages", new Map[]{message});

        // Create HTTP headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            // Make API call
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                return sanitizePost(extractContentFromResponse(response.getBody()));
            } else {
                throw new Exception("Mistral API error: " + response.getStatusCode() + " - " + response.getBody());
            }
        } catch (Exception e) {
            throw new Exception("Failed to generate post: " + e.getMessage());
        }
    }

    /**
     * Generate a batch of new topic prompts for a category, using the same
     * Mistral token/setup as post generation. Existing topics (both the
     * built-in ones and any already-loaded AI topics) are sent along so the
     * model avoids repeating or closely paraphrasing them.
     */
    public List<String> generateMoreTopics(String categoryId, List<String> existingTopics) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("Mistral API key is not configured");
        }

        String prompt = buildTopicsPrompt(categoryId, existingTopics);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "mistral-small-latest");
        requestBody.put("temperature", 0.9);
        requestBody.put("max_tokens", 500);

        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        requestBody.put("messages", new Map[]{message});

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                String content = extractContentFromResponse(response.getBody());
                return parseTopicsList(content, existingTopics);
            } else {
                throw new Exception("Mistral API error: " + response.getStatusCode() + " - " + response.getBody());
            }
        } catch (Exception e) {
            throw new Exception("Failed to generate topics: " + e.getMessage());
        }
    }

    /**
     * Build a detailed prompt based on topic and category.
     *
     * This content goes straight to the review/publish flow, so the prompt
     * is written as a direct-to-publish spec: the company name and tagline
     * are fixed and injected as real text (never as placeholders the model
     * has to fill in), and the model is explicitly told never to emit
     * unresolved variables, bracketed instructions, or escaped line breaks.
     * sanitizePost() below is a second line of defense in case the model
     * doesn't fully comply.
     */
    private String buildPrompt(String topic, String categoryId) {
        String categoryContext = getCategoryContext(categoryId);

        return String.format(
            "You are a professional LinkedIn content creator writing on behalf of the company " +
            "\"%s\" (tagline: \"%s\") — a structural steel detailing company. This is the ONLY " +
            "company this post is ever about.\n\n" +
            "CONTEXT: %s\n\n" +
            "TOPIC: %s\n\n" +
            "REQUIREMENTS:\n" +
            "- Write a compelling, engaging LinkedIn post (150-200 words)\n" +
            "- Professional yet conversational tone\n" +
            "- Include specific steel detailing industry insights\n" +
            "- Add 3-5 relevant hashtags at the end\n" +
            "- No markdown or special formatting (no **bold**, no asterisks)\n" +
            "- Add value with actionable insights\n" +
            "- End with a question or call-to-action to encourage engagement\n" +
            "- Make it sound authentic and personal\n" +
            "- Separate paragraphs with an actual blank line (a real line break you type), " +
            "never the two characters backslash-n, and never the characters /n\n\n" +
            "HARD RULES (this text is sent directly to the user for review and posting, so it " +
            "must be complete, final, publication-ready content — nothing left for a human to " +
            "fill in):\n" +
            "1. If you name the company at all, you MUST call it exactly \"%s\" — never invent, " +
            "substitute, or make up any other company name (e.g. do not write things like " +
            "\"SteelFrame Precision\", \"Precision Steel Co.\", or any other invented name).\n" +
            "2. Never invent specific numbers you don't actually have (years in business, " +
            "project counts, staff size, etc.) and never write a bracketed placeholder for one " +
            "like [X], [X years], [insert number]. Instead phrase it without a number — say " +
            "\"years of hands-on experience\" rather than \"[X] years of experience\".\n" +
            "3. Never output any placeholder or template marker of any kind — no [Company Name], " +
            "[your company], {{company_name}}, {tagline}, <insert>, $variable, [Insert CTA here], " +
            "\"Add hashtags\", \"Example:\", or similar. Write the actual finished content instead.\n" +
            "4. If some other detail (a date, a person's name, a link) isn't given to you and " +
            "isn't essential to the post, leave it out entirely rather than inventing a " +
            "placeholder or a fake value for it.\n\n" +
            "Write the post directly (no preamble, no meta-commentary):",
            COMPANY_NAME,
            COMPANY_TAGLINE,
            categoryContext,
            topic,
            COMPANY_NAME
        );
    }

    /**
     * Final safety net before content leaves the backend: even though the
     * prompt instructs the model not to emit placeholders or escape
     * sequences, models don't always fully comply. This normalizes escaped
     * line breaks into real ones, swaps any lingering company-name/tagline
     * placeholders for the real values, and strips obvious leftover
     * template/instruction fragments so nothing unresolved reaches the
     * frontend.
     */
    private String sanitizePost(String content) {
        if (content == null) {
            return content;
        }

        String result = content;

        // Normalize escaped/literal line-break sequences into real newlines.
        result = result.replace("\\r\\n", "\n");
        result = result.replace("\\n", "\n");
        result = result.replace("/n/n", "\n\n");
        result = result.replace("/n", "\n");

        // Resolve any lingering company-name / tagline placeholders.
        result = result.replaceAll(
            "(?i)\\[\\s*(your\\s+)?company(\\s+name)?\\s*\\]|\\{\\{\\s*company_?name\\s*\\}\\}|" +
            "\\{\\s*company(\\s+name)?\\s*\\}|<\\s*company(\\s+name)?\\s*>|\\$company_?name",
            COMPANY_NAME
        );
        result = result.replaceAll(
            "(?i)\\[\\s*(your\\s+)?tagline\\s*\\]|\\{\\{\\s*tagline\\s*\\}\\}|" +
            "\\{\\s*tagline\\s*\\}|<\\s*tagline\\s*>|\\$tagline",
            COMPANY_TAGLINE
        );

        // Strip obvious leftover template instructions / unresolved
        // variables (bracketed/braced fragments containing instruction-like
        // words such as insert, add, replace, placeholder, enter, etc.).
        result = result.replaceAll(
            "(?i)[\\[{<]\\s*(insert|add|replace|enter|write|placeholder|your|example)[^\\]}>]*[\\]}>]",
            ""
        );

        // Catch-all: the post shouldn't contain bracket/brace/angle-bracket
        // wrapped text at all (e.g. leftover numeric placeholders like [X],
        // [X years], [number]). Anything still wrapped like that at this
        // point is a placeholder, not real content, so drop it.
        result = result.replaceAll("\\[[^\\]]{0,60}\\]", "");
        result = result.replaceAll("\\{[^}]{0,60}\\}", "");
        result = result.replaceAll("<[^>]{0,60}>", "");

        // Strip markdown bold/italic asterisks (post requires plain text).
        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        result = result.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "$1");

        // Collapse any resulting excess blank lines/spaces left by stripping.
        result = result.replaceAll("[ \\t]+\\n", "\n");
        result = result.replaceAll("\\n{3,}", "\n\n");
        result = result.replaceAll("[ \\t]{2,}", " ");
        result = result.replaceAll(" +([,.!?])", "$1");
        result = result.trim();

        return result;
    }

    /**
     * Build a prompt asking Mistral for a fresh batch of topic prompts for a
     * category, explicitly excluding anything already shown to the user.
     */
    private String buildTopicsPrompt(String categoryId, List<String> existingTopics) {
        String categoryContext = getCategoryContext(categoryId);
        String existingList = (existingTopics == null || existingTopics.isEmpty())
            ? "None yet."
            : String.join("\n", existingTopics);

        return String.format(
            "You are a professional LinkedIn content strategist specializing in structural steel detailing and construction.\n\n" +
            "CONTEXT: %s\n\n" +
            "TOPICS ALREADY SHOWN TO THE USER (do not repeat or closely paraphrase any of these):\n%s\n\n" +
            "TASK: Write 5 brand-new, distinct LinkedIn post topic prompts for this category. " +
            "Each one should be a single sentence written as an instruction describing what the post " +
            "should cover, in the same style as the topics above, and specific to structural steel detailing.\n\n" +
            "OUTPUT FORMAT: Respond with ONLY a valid JSON array of 5 strings and nothing else - " +
            "no preamble, no numbering, no markdown code fences. " +
            "Example: [\"Explain how X improves Y.\", \"Share a story about Z.\"]",
            categoryContext,
            existingList
        );
    }

    /**
     * Get context based on category
     */
    private String getCategoryContext(String categoryId) {
        Map<String, String> categoryContexts = new HashMap<>();
        categoryContexts.put("showcase", 
            "You're showcasing a steel detailing project. Focus on challenges, solutions, and results. " +
            "Highlight precision, technical expertise, and successful outcomes. Be specific about the project type.");
        
        categoryContexts.put("insights", 
            "You're sharing industry insights about steel detailing. Focus on education, best practices, " +
            "and the importance of accurate detailing in construction. Share recent trends or innovations.");
        
        categoryContexts.put("technical", 
            "You're discussing technical aspects of steel detailing. Focus on specific processes, " +
            "tools like Tekla, BIM, clash detection, and fabrication efficiency. Explain technical concepts simply.");
        
        categoryContexts.put("branding", 
            "You're promoting your steel detailing company. Focus on expertise, quality commitment, " +
            "team experience, and company values. Emphasize what makes your company unique.");
        
        categoryContexts.put("educational", 
            "You're educating about steel detailing fundamentals. Focus on explaining concepts, " +
            "processes, and the value of professional detailing services. Make it accessible to newcomers.");
        
        categoryContexts.put("client", 
            "You're speaking to clients and fabricators. Focus on benefits, cost savings, " +
            "time efficiency, and how detailing services support their success. Include client success stories.");
        
        categoryContexts.put("engagement", 
            "You're engaging with the community. Focus on asking questions, sharing experiences, " +
            "and encouraging discussion about steel detailing challenges and trends. Be conversational.");
        
        categoryContexts.put("aiprompts", 
            "You're creating visual content prompts. Focus on describing images and scenes " +
            "that showcase steel detailing work, models, and construction progress. Be descriptive and visual.");
        
        categoryContexts.put("hooks", 
            "You're creating attention-grabbing posts. Focus on powerful statements, " +
            "memorable phrases, and engaging hooks that drive engagement. Start with a strong opening.");
        
        return categoryContexts.getOrDefault(categoryId, 
            "You're creating content about steel detailing in construction. Focus on quality and precision.");
    }

    /**
     * Extract content from Mistral API response
     */
    private String extractContentFromResponse(String responseBody) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            
            if (choices != null && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    JsonNode contentNode = message.get("content");
                    if (contentNode != null) {
                        String content = contentNode.asText();
                        // Clean up the content
                        content = content.replaceAll("^[\"\'\n\r]+|[\"\'\n\r]+$", "");
                        return content.trim();
                    }
                }
            }
            
            throw new Exception("Could not extract content from Mistral response");
        } catch (Exception e) {
            throw new Exception("Failed to parse Mistral response: " + e.getMessage());
        }
    }

    /**
     * Parse the model's topic-list response into a clean list of strings.
     * Tries strict JSON array parsing first (the format we asked for), and
     * falls back to line-by-line parsing (stripping numbering/bullets/quotes)
     * if the model didn't return valid JSON. Also de-duplicates against the
     * topics the frontend told us it already has.
     */
    private List<String> parseTopicsList(String content, List<String> existingTopics) {
        List<String> topics = new ArrayList<>();
        String cleaned = content == null ? "" : content.trim();

        // Strip markdown code fences if the model added them anyway
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String t = item.asText().trim();
                    if (!t.isEmpty()) {
                        topics.add(t);
                    }
                }
            }
        } catch (Exception jsonParseFailed) {
            // Fallback: one topic per line, strip common list markers
            for (String line : cleaned.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                t = t.replaceAll("^\\d+[\\.\\)]\\s*", "");
                t = t.replaceAll("^[-*•]\\s*", "");
                t = t.replaceAll("^[\"']|[\"',]$", "");
                t = t.trim();
                if (!t.isEmpty()) {
                    topics.add(t);
                }
            }
        }

        // De-duplicate against what the client already has, and within this batch
        Set<String> existingLower = new HashSet<>();
        if (existingTopics != null) {
            for (String existing : existingTopics) {
                if (existing != null) {
                    existingLower.add(existing.trim().toLowerCase());
                }
            }
        }

        List<String> deduped = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        for (String t : topics) {
            String lower = t.toLowerCase();
            if (!existingLower.contains(lower) && !seenInBatch.contains(lower)) {
                deduped.add(t);
                seenInBatch.add(lower);
            }
        }

        return deduped;
    }
}