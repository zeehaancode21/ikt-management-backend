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
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates post-attachment images using Pollinations.ai — a free,
 * no-API-key-required image generation service (https://pollinations.ai).
 *
 * This replaces the OpenAI gpt-image-1 integration, which required a
 * funded + verified OpenAI organization and kept hitting 429 rate limits
 * on low usage tiers. Pollinations has no billing/verification gate, so
 * there's nothing to get blocked on account-side.
 *
 * Two modes, matching the old contract:
 *  - No reference image -> plain text-to-image via the "flux" model
 *  - Reference image present -> image-guided generation via the "kontext"
 *    model, which fetches the reference from a public URL. Since the
 *    caller gives us a base64 blob (not a URL), we briefly re-host it via
 *    ReferenceImageCacheService and pass Pollinations that temporary URL.
 *
 * The public contract — generateImages(prompt, referenceImages, count)
 * returning raw image bytes per image — is unchanged, so AiImageController
 * did not need to change how it invokes this service.
 */
@Service
public class AiImageGenerationService {
    private static final Logger logger = LoggerFactory.getLogger(AiImageGenerationService.class);

    // Pollinations' unified image endpoint. No API key required for normal
    // usage; an optional key can be set for higher throughput (see
    // pollinations.ai) but is not needed for this to work.
    @Value("${pollinations.api.url:https://image.pollinations.ai/prompt}")
    private String apiUrl;

    @Value("${pollinations.api.key:}")
    private String apiKey;

    @Value("${ai.image.model:flux}")
    private String textToImageModel;

    @Value("${ai.image.edit-model:kontext}")
    private String imageGuidedModel;

    @Value("${ai.image.width:1024}")
    private int width;

    @Value("${ai.image.height:1024}")
    private int height;

    private static final int MAX_IMAGES_PER_REQUEST = 10;

    // Pollinations is a shared free/community service — we still avoid
    // hammering it. Minimum gap between the start of one call and the next.
    private static final long MIN_INTERVAL_BETWEEN_REQUESTS_MS = 3_000;
    private static final long DEFAULT_RETRY_WAIT_MS = 8_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ReferenceImageCacheService referenceImageCacheService;
    private final Random random = new Random();

    private final AtomicBoolean generationInProgress = new AtomicBoolean(false);
    private final AtomicLong nextAllowedRequestTime = new AtomicLong(0);

    public AiImageGenerationService(RestTemplate restTemplate,
                                     ReferenceImageCacheService referenceImageCacheService) {
        this.restTemplate = restTemplate;
        this.referenceImageCacheService = referenceImageCacheService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generates one or more images for the given prompt via Pollinations.ai.
     * No API key is required. Only one call is processed at a time
     * app-wide; a second concurrent call is rejected immediately instead
     * of being sent out, to avoid piling up requests.
     */
    public List<byte[]> generateImages(String prompt, List<String> referenceImages, int count) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new AiImageGenerationException("Prompt is required to generate an image");
        }

        if (!generationInProgress.compareAndSet(false, true)) {
            logger.warn("Rejected image generation request: another generation is already in progress");
            throw new AiImageGenerationException(
                "An image generation request is already in progress. Please wait for it to finish before starting another one.");
        }

        try {
            int requestedCount = clampCount(count);
            List<String> refs = referenceImages == null
                ? Collections.emptyList()
                : referenceImages.stream().filter(s -> s != null && !s.isBlank()).toList();

            String referencePublicUrl = null;
            if (!refs.isEmpty()) {
                try {
                    referencePublicUrl = referenceImageCacheService.cacheAndGetPublicUrl(refs.get(0));
                } catch (Exception e) {
                    throw new AiImageGenerationException("Could not prepare reference image: " + e.getMessage());
                }
            }

            logger.info("Generating {} image(s) via Pollinations.ai model {} ({} reference image(s) supplied)",
                requestedCount, refs.isEmpty() ? textToImageModel : imageGuidedModel, refs.size());

            List<byte[]> images = new ArrayList<>();
            for (int i = 0; i < requestedCount; i++) {
                waitForNextAllowedSlot();
                images.add(fetchOneImage(prompt, referencePublicUrl));
            }
            return images;
        } finally {
            generationInProgress.set(false);
        }
    }

    private int clampCount(int count) {
        if (count < 1) {
            return 1;
        }
        return Math.min(count, MAX_IMAGES_PER_REQUEST);
    }

    private void waitForNextAllowedSlot() {
        long now = System.currentTimeMillis();
        long allowedAt = nextAllowedRequestTime.get();
        if (now < allowedAt) {
            sleep(allowedAt - now);
        }
        nextAllowedRequestTime.set(System.currentTimeMillis() + MIN_INTERVAL_BETWEEN_REQUESTS_MS);
    }

    private byte[] fetchOneImage(String prompt, String referencePublicUrl) {
        boolean guided = referencePublicUrl != null;
        // A different seed per image so requesting count > 1 doesn't return
        // near-identical images.
        long seed = Math.abs(random.nextLong() % 1_000_000_000L);

        UriComponentsBuilder builder = UriComponentsBuilder
            .fromHttpUrl(apiUrl + "/" + encodePathSegment(prompt))
            .queryParam("width", width)
            .queryParam("height", height)
            .queryParam("seed", seed)
            .queryParam("model", guided ? imageGuidedModel : textToImageModel)
            .queryParam("nologo", "true")
            .queryParam("private", "true");

        if (guided) {
            builder.queryParam("image", referencePublicUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.queryParam("key", apiKey);
        }

        String url = builder.build(true).toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.ALL));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<byte[]> response =
                restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().length > 0) {
                return response.getBody();
            }
            throw new AiImageGenerationException("Pollinations.ai returned an empty or unexpected response");
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Rate limited by Pollinations.ai, waiting {}ms and retrying once", DEFAULT_RETRY_WAIT_MS);
            sleep(DEFAULT_RETRY_WAIT_MS);
            try {
                ResponseEntity<byte[]> retryResponse =
                    restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
                if (retryResponse.getStatusCode().is2xxSuccessful() && retryResponse.getBody() != null) {
                    return retryResponse.getBody();
                }
                throw new AiImageGenerationException("Pollinations.ai is currently busy. Please try again shortly.");
            } catch (RestClientException retryEx) {
                throw new AiImageGenerationException("Pollinations.ai is currently busy. Please try again shortly.");
            }
        } catch (HttpClientErrorException e) {
            String detail = extractErrorMessage(e.getResponseBodyAsString());
            logger.error("Pollinations.ai request rejected: {} - {}", e.getStatusCode(), detail);
            throw new AiImageGenerationException("Image generation failed: " + detail);
        } catch (RestClientException e) {
            logger.error("Network error calling Pollinations.ai: {}", e.getMessage());
            throw new AiImageGenerationException("Failed to reach the image generation service: " + e.getMessage());
        }
    }

    private String encodePathSegment(String prompt) {
        return java.net.URLEncoder.encode(prompt, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "Unknown error";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.get("error");
            if (error != null && error.get("message") != null) {
                return error.get("message").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return body;
    }

    /**
     * Thrown for any image-generation failure (bad prompt, provider busy,
     * network failure, an already-in-progress request, or a bad reference
     * image). The controller catches this and turns it into a clean JSON
     * error response instead of a stack trace.
     */
    public static class AiImageGenerationException extends RuntimeException {
        public AiImageGenerationException(String message) {
            super(message);
        }
    }
}