package com.example.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mistral client for LinkedIn post + topic generation.
 *
 * Token/latency savers (see batch 2 notes):
 *  - short prompts (the sanitizePost() safety net already enforces the hard rules)
 *  - blank/null topics are rejected BEFORE any API call
 *  - topic generation asks for 10 topics per call, returns 5, and keeps the rest
 *    in memory, so every second click costs zero tokens
 *  - only the newest existing topics are re-sent, each truncated
 *  - after a 429 the API is not called again until the cooldown ends
 *  - connect/read timeouts so a hung call can never pin a thread
 *  - one log line per call with real prompt/completion token counts
 *
 * Post generation is intentionally NOT cached: "Generate" is also used to get a
 * different draft, and recurring scheduled posts must not repeat word for word.
 */
@Service
public class MistralService {

    private static final Logger log = LoggerFactory.getLogger(MistralService.class);

    // Fixed company branding — always used verbatim, never asked for again,
    // never left as a placeholder. See buildPrompt()/sanitizePost().
    private static final String COMPANY_NAME = "IK Tangience";
    private static final String COMPANY_TAGLINE = "We Speak Fluent Steel";

    private static final String MODEL = "mistral-small-latest";

    // Ceilings, not targets: you only pay for tokens actually generated.
    private static final int POST_MAX_TOKENS = 320;
    private static final int TOPICS_MAX_TOKENS = 600;

    private static final int TOPICS_RETURNED_PER_CALL = 5;
    private static final int TOPICS_REQUESTED_PER_CALL = 10;
    private static final int MAX_BUFFERED_TOPICS = 20;
    private static final int MAX_EXISTING_TOPICS_SENT = 12;
    private static final int MAX_EXISTING_TOPIC_CHARS = 90;

    // Abuse guard only; long custom prompts still go through untouched.
    private static final int MAX_TOPIC_INPUT_CHARS = 1500;

    private static final long DEFAULT_COOLDOWN_MS = 30_000;
    private static final long MAX_COOLDOWN_MS = 120_000;

    private static final String DEFAULT_CONTEXT_KEY = "default";
    private static final Map<String, String> CATEGORY_CONTEXTS = Map.of(
        "showcase", "Showcase a steel detailing project: challenges, solutions, results, precision. Be specific about the project type.",
        "insights", "Share industry insights: best practices, why accurate detailing matters, recent trends or innovations.",
        "technical", "Discuss technical aspects: processes, Tekla, BIM, clash detection, fabrication efficiency. Explain simply.",
        "branding", "Promote the company: expertise, quality commitment, team experience, values, what makes it unique.",
        "educational", "Educate on detailing fundamentals: concepts, processes, value of professional detailing. Newcomer-friendly.",
        "client", "Speak to clients and fabricators: benefits, cost and time savings, how detailing supports their success.",
        "engagement", "Engage the community: ask questions, share experiences, invite discussion on detailing challenges. Conversational.",
        "aiprompts", "Create visual content prompts: describe images and scenes of detailing work, models and construction progress.",
        "hooks", "Write attention-grabbing posts: powerful statements, memorable phrases, a strong opening line."
    );
    private static final String DEFAULT_CONTEXT =
        "Create content about steel detailing in construction. Focus on quality and precision.";

    @Value("${mistral.api.key:}")
    private String apiKey;

    @Value("${mistral.api.url:https://api.mistral.ai/v1/chat/completions}")
    private String apiUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Spare topics per category (in memory; lost on restart, which is fine).
    private final Map<String, Deque<String>> topicBuffer = new ConcurrentHashMap<>();

    // Epoch millis until which we do not call Mistral (set after a 429).
    private volatile long blockedUntil = 0L;

    public MistralService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(45_000);
        this.restTemplate = new RestTemplate(factory);
    }

    // ───────────────────────── Public API (signatures unchanged) ─────────────────────────

    /** Generate a LinkedIn post using Mistral AI. */
    public String generatePost(String topic, String categoryId) throws Exception {
        if (topic == null || topic.isBlank()) {
            // Previously this sent the literal text "null" to the model and paid for the answer.
            throw new Exception("A topic is required to generate a post");
        }
        String prompt = buildPrompt(truncate(topic.trim(), MAX_TOPIC_INPUT_CHARS), categoryId);
        try {
            return sanitizePost(callMistral(prompt, 0.7, POST_MAX_TOKENS));
        } catch (Exception e) {
            throw new Exception("Failed to generate post: " + e.getMessage());
        }
    }

    /**
     * Generate new topic prompts for a category. Asks the model for 10, returns 5 and
     * keeps the rest, so the next request is usually served with no API call at all.
     * Buffered topics are re-checked against the client's current list when served.
     */
    public List<String> generateMoreTopics(String categoryId, List<String> existingTopics) throws Exception {
        Set<String> seen = new HashSet<>();
        if (existingTopics != null) {
            for (String t : existingTopics) {
                if (t != null) seen.add(t.trim().toLowerCase());
            }
        }

        Deque<String> buffer = topicBuffer.computeIfAbsent(bufferKey(categoryId), k -> new ArrayDeque<>());

        // One generation per category at a time: a double-click waits, then finds the buffer filled.
        synchronized (buffer) {
            List<String> result = new ArrayList<>();

            while (result.size() < TOPICS_RETURNED_PER_CALL && !buffer.isEmpty()) {
                String t = buffer.pollFirst();
                if (seen.add(t.toLowerCase())) result.add(t);
            }
            if (result.size() >= TOPICS_RETURNED_PER_CALL) {
                log.info("Topics for '{}' served from buffer (0 tokens)", bufferKey(categoryId));
                return result;
            }

            List<String> fresh;
            try {
                String content = callMistral(buildTopicsPrompt(categoryId, existingTopics), 0.9, TOPICS_MAX_TOKENS);
                fresh = parseTopicsList(content);
            } catch (Exception e) {
                if (!result.isEmpty()) return result; // give what we have instead of failing
                throw new Exception("Failed to generate topics: " + e.getMessage());
            }

            for (String t : fresh) {
                if (!seen.add(t.toLowerCase())) continue; // already shown / duplicate
                if (result.size() < TOPICS_RETURNED_PER_CALL) {
                    result.add(t);
                } else if (buffer.size() < MAX_BUFFERED_TOPICS
                        && buffer.stream().noneMatch(b -> b.equalsIgnoreCase(t))) {
                    buffer.addLast(t);
                }
            }
            return result;
        }
    }

    // ───────────────────────── HTTP ─────────────────────────

    private String callMistral(String prompt, double temperature, int maxTokens) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new Exception("Mistral API key is not configured");
        }

        long waitMs = blockedUntil - System.currentTimeMillis();
        if (waitMs > 0) {
            throw new Exception("The AI service is rate-limited right now. Please try again in "
                    + ((waitMs + 999) / 1000) + " seconds.");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new Exception("Mistral API error: " + response.getStatusCode());
            }
            return extractContentFromResponse(response.getBody());
        } catch (HttpClientErrorException.TooManyRequests e) {
            long cooldown = cooldownMs(e);
            blockedUntil = System.currentTimeMillis() + cooldown;
            log.warn("Mistral rate limit hit; pausing calls for {} ms", cooldown);
            throw new Exception("The AI service is rate-limited right now. Please try again in about "
                    + (cooldown / 1000) + " seconds.");
        } catch (HttpStatusCodeException e) {
            throw new Exception("Mistral API error: " + e.getStatusCode().value() + " "
                    + truncate(e.getResponseBodyAsString(), 200));
        } catch (ResourceAccessException e) {
            throw new Exception("The AI service timed out or is unreachable. Please try again.");
        }
    }

    private long cooldownMs(HttpClientErrorException e) {
        try {
            String retryAfter = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("Retry-After") : null;
            if (retryAfter != null) {
                return Math.min(Math.max(Long.parseLong(retryAfter.trim()) * 1000, 1_000), MAX_COOLDOWN_MS);
            }
        } catch (NumberFormatException ignored) {
            // fall through to default
        }
        return DEFAULT_COOLDOWN_MS;
    }

    private String extractContentFromResponse(String responseBody) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode usage = root.get("usage");
            if (usage != null) {
                log.info("Mistral tokens: prompt={} completion={} total={}",
                        usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt());
            }

            JsonNode choices = root.get("choices");
            if (choices != null && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.get("content") != null) {
                    String content = message.get("content").asText();
                    content = content.replaceAll("^[\"'\\n\\r]+|[\"'\\n\\r]+$", "");
                    return content.trim();
                }
            }
            throw new Exception("Could not extract content from Mistral response");
        } catch (Exception e) {
            throw new Exception("Failed to parse Mistral response: " + e.getMessage());
        }
    }

    // ───────────────────────── Prompts ─────────────────────────

    /**
     * Short, direct-to-publish prompt. The company name/tagline are injected as real
     * text, and sanitizePost() below is still the safety net for placeholders,
     * escaped line breaks and markdown.
     */
    private String buildPrompt(String topic, String categoryId) {
        return String.format(
            "Write a LinkedIn post for \"%s\" (tagline: \"%s\"), a structural steel detailing company. " +
            "Output only the finished post.\n" +
            "Angle: %s\n" +
            "Topic: %s\n" +
            "Rules:\n" +
            "- 150-200 words; professional yet conversational; specific steel detailing insight; actionable value.\n" +
            "- Plain text only (no markdown or asterisks). Separate paragraphs with real blank lines, never \"\\n\" or \"/n\".\n" +
            "- End with a question or call to action, then 3-5 hashtags.\n" +
            "- The only company name is \"%s\". Never invent other company names, numbers, dates, names or links.\n" +
            "- No placeholders or brackets of any kind ([X], {..}, <..>); leave out any detail you don't have.",
            COMPANY_NAME, COMPANY_TAGLINE, getCategoryContext(categoryId), topic, COMPANY_NAME);
    }

    private String buildTopicsPrompt(String categoryId, List<String> existingTopics) {
        StringBuilder existing = new StringBuilder();
        if (existingTopics != null) {
            List<String> clean = new ArrayList<>();
            for (String t : existingTopics) {
                if (t != null && !t.isBlank()) clean.add(t.trim());
            }
            int from = Math.max(0, clean.size() - MAX_EXISTING_TOPICS_SENT); // newest ones only
            for (String t : clean.subList(from, clean.size())) {
                existing.append("- ").append(truncate(t, MAX_EXISTING_TOPIC_CHARS)).append('\n');
            }
        }
        if (existing.length() == 0) existing.append("- (none)\n");

        return String.format(
            "You are a LinkedIn content strategist for structural steel detailing.\n" +
            "Angle: %s\n" +
            "Already used (do not repeat or paraphrase):\n%s" +
            "Write %d new, distinct post-topic prompts. Each is one sentence phrased as an instruction " +
            "(e.g. \"Explain how X improves Y.\"), specific to steel detailing.\n" +
            "Reply with ONLY a JSON array of %d strings, no markdown fences.",
            getCategoryContext(categoryId), existing, TOPICS_REQUESTED_PER_CALL, TOPICS_REQUESTED_PER_CALL);
    }

    private String getCategoryContext(String categoryId) {
        return CATEGORY_CONTEXTS.getOrDefault(categoryId == null ? "" : categoryId, DEFAULT_CONTEXT);
    }

    private String bufferKey(String categoryId) {
        return (categoryId != null && CATEGORY_CONTEXTS.containsKey(categoryId)) ? categoryId : DEFAULT_CONTEXT_KEY;
    }

    // ───────────────────────── Output cleanup ─────────────────────────

    /**
     * Final safety net before content leaves the backend: normalizes escaped line
     * breaks, swaps lingering company-name/tagline placeholders for the real values,
     * and strips leftover template fragments. (Unchanged from the previous version.)
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

    /** Parses the model's topic list: strict JSON array first, line-by-line fallback. */
    private List<String> parseTopicsList(String content) {
        List<String> topics = new ArrayList<>();
        String cleaned = content == null ? "" : content.trim();

        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }

        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String t = item.asText().trim();
                    if (!t.isEmpty()) topics.add(t);
                }
            }
        } catch (Exception jsonParseFailed) {
            for (String line : cleaned.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                t = t.replaceAll("^\\d+[\\.\\)]\\s*", "");
                t = t.replaceAll("^[-*•]\\s*", "");
                t = t.replaceAll("^[\"']|[\"',]$", "");
                t = t.trim();
                if (!t.isEmpty()) topics.add(t);
            }
        }
        return topics;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}