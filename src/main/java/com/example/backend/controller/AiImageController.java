package com.example.backend.controller;

import com.example.backend.service.AiImageDraftStore;
import com.example.backend.service.AiImageDraftStore.GeneratedImageOption;
import com.example.backend.service.AiImageGenerationService.AiImageGenerationException;
import com.example.backend.service.TemplateImageComposerService;
import com.example.backend.service.TemplateImageComposerService.ImageCompositionException;
import com.example.backend.service.TemplatedImageGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/social-post/ai-image")
@CrossOrigin(origins = "*")
public class AiImageController {
    private static final Logger logger = LoggerFactory.getLogger(AiImageController.class);

    @Autowired
    private AiImageDraftStore aiImageDraftStore;

    @Autowired
    private TemplatedImageGenerationService templatedImageGenerationService;

    @Autowired
    private TemplateImageComposerService templateImageComposerService;

    @Value("${ai.image.count:1}")
    private int defaultCount;

    @Value("${ai.image.timeout-ms:60000}")
    private long timeoutMs;

    // ===== DRAFT ENDPOINTS =====

    @GetMapping("/draft/{draftId}")
    public ResponseEntity<?> getDraft(@PathVariable String draftId) {
        try {
            logger.info("Fetching draft: {}", draftId);

            if (draftId == null || draftId.equals("undefined") || draftId.equals("null")) {
                draftId = UUID.randomUUID().toString();
                logger.info("Created new draft ID: {}", draftId);
            }

            List<GeneratedImageOption> images = aiImageDraftStore.getOptions(draftId);
            Integer selectedImageId = aiImageDraftStore.getSelectedImageId(draftId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("id", draftId);
            response.put("images", images);
            response.put("selectedImageId", selectedImageId); // may be null - no selection yet
            response.put("imageCount", images.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to fetch draft: {}", draftId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to fetch draft: " + e.getMessage()
                ));
        }
    }

    @PutMapping("/draft/{draftId}")
    public ResponseEntity<?> updateDraft(@PathVariable String draftId, @RequestBody Map<String, Object> draftData) {
        try {
            logger.info("Updating draft: {} with data: {}", draftId, draftData);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("draftId", draftId);
            response.put("message", "Draft updated successfully");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to update draft: {}", draftId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to update draft: " + e.getMessage()
                ));
        }
    }

    @DeleteMapping("/draft/{draftId}")
    public ResponseEntity<?> deleteDraft(@PathVariable String draftId) {
        try {
            logger.info("Deleting draft: {}", draftId);

            aiImageDraftStore.clear(draftId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Draft deleted successfully"
            ));

        } catch (Exception e) {
            logger.error("Failed to delete draft: {}", draftId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to delete draft: " + e.getMessage()
                ));
        }
    }

    // ===== UPLOADED IMAGE ENDPOINT =====
    //
    // An uploaded image gets the exact same company-template treatment as
    // an AI-generated one: it is composited into the fixed IK Tangience
    // template's content area so every final post — regardless of how the
    // image was sourced — is branded consistently. This is not a mode the
    // user opts into; it always happens for every uploaded image.

    @PostMapping(value = "/compose-uploaded", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> composeUploadedImage(@RequestBody ComposeUploadedRequest request) {
        try {
            if (request.getImageBase64() == null || request.getImageBase64().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "An image is required"
                ));
            }
            byte[] imageBytes = decodeImageBase64(request.getImageBase64());
            byte[] composed = templateImageComposerService.composeWithTemplate(imageBytes);
            String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(composed);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "image", dataUrl,
                "message", "Image composed into the company template successfully"
            ));

        } catch (ImageCompositionException e) {
            logger.warn("Compose-uploaded rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                    "success", false,
                    "message", e.getMessage()
                ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Could not read the uploaded image: " + e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Failed to compose uploaded image into template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to apply the company template: " + e.getMessage()
                ));
        }
    }

    /** Strips a "data:image/...;base64," prefix if present and decodes the rest. */
    private byte[] decodeImageBase64(String value) {
        String raw = value;
        int commaIdx = raw.indexOf(',');
        if (raw.startsWith("data:") && commaIdx != -1) {
            raw = raw.substring(commaIdx + 1);
        }
        return Base64.getDecoder().decode(raw);
    }

    // ===== TEMPLATED GENERATION ENDPOINTS =====
    //
    // Every AI-generated image goes through this flow: the user's prompt
    // (sent to the AI exactly as typed, never modified) controls the
    // content dropped into the fixed IK Tangience template's content
    // area — the header, footer, logo, slogan and CONNECT NOW block always
    // come from the template file untouched. See
    // TemplatedImageGenerationService and TemplateImageComposerService for
    // how that's enforced. Applying the template is always-on and is not a
    // separate user-selectable mode.

    /**
     * Kept for backward compatibility with any existing frontend build
     * that still polls this endpoint. The bundled company template
     * (src/main/resources/public/template.png) is loaded once at startup
     * and application startup fails if it can't be — so on any running
     * instance this always reports true; there is no runtime "unavailable"
     * state to gate the UI on anymore.
     */
    @GetMapping("/template-status")
    public ResponseEntity<?> templateStatus() {
        return ResponseEntity.ok(Map.of(
            "available", templateImageComposerService.isTemplateAvailable()
        ));
    }

    @PostMapping(value = "/generate-templated", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generateTemplatedImage(@RequestBody AiImageRequest request) {
        try {
            long startTime = System.currentTimeMillis();

            String draftId = request.getDraftId();
            if (draftId == null || draftId.equals("undefined") || draftId.equals("null")) {
                draftId = UUID.randomUUID().toString();
                logger.info("Generated new draft ID: {}", draftId);
            }

            if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Prompt is required"
                ));
            }

            logger.info("Generating templated image(s) for draft: {} with prompt: {}", draftId, request.getPrompt());

            int count = request.getCount() != null && request.getCount() > 0 ? request.getCount() : defaultCount;
            final String finalDraftId = draftId;

            CompletableFuture<List<byte[]>> future = CompletableFuture.supplyAsync(() ->
                templatedImageGenerationService.generateTemplatedImages(request.getPrompt(), count)
            );

            List<byte[]> generatedImages;
            try {
                generatedImages = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                logger.warn("Templated image generation timed out after {}ms", timeoutMs);
                future.cancel(true);
                return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                    .body(Map.of(
                        "success", false,
                        "message", "Image generation is taking too long. Please try again."
                    ));
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof AiImageGenerationException || cause instanceof ImageCompositionException) {
                    logger.warn("Templated image generation failed for draft {}: {}", finalDraftId, cause.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                            "success", false,
                            "message", cause.getMessage()
                        ));
                }
                logger.error("Templated image generation failed", cause);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                        "success", false,
                        "message", "Failed to generate image: " + cause.getMessage()
                    ));
            }

            List<GeneratedImageOption> options = aiImageDraftStore.saveGenerated(finalDraftId, generatedImages, "image/png");
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Generated {} templated image(s) in {}ms for draft {}", options.size(), duration, finalDraftId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "images", options,
                "draftId", finalDraftId,
                "count", options.size(),
                "duration", duration,
                "message", "Generated " + options.size() + " templated image option" + (options.size() == 1 ? "" : "s") + " successfully"
            ));

        } catch (ImageCompositionException e) {
            logger.warn("Templated image generation rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                    "success", false,
                    "message", e.getMessage()
                ));
        } catch (AiImageGenerationException e) {
            logger.warn("Templated image generation rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                    "success", false,
                    "message", e.getMessage()
                ));
        } catch (Exception e) {
            logger.error("Templated image generation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to generate image: " + e.getMessage()
                ));
        }
    }

    // ===== SELECTION ENDPOINT =====

    /** Marks one previously generated option as the finalized image for its draft/post. */
    @PostMapping("/{imageId}/select")
    public ResponseEntity<?> selectImage(@PathVariable int imageId) {
        try {
            logger.info("Selecting generated image: {}", imageId);

            GeneratedImageOption selected = aiImageDraftStore.select(imageId);
            if (selected == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "That generated image could not be found (it may have expired)."
                ));
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "image", selected,
                "message", "Image selected successfully"
            ));

        } catch (Exception e) {
            logger.error("Failed to select image: {}", imageId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to select image: " + e.getMessage()
                ));
        }
    }

    // ===== REFERENCE IMAGE ENDPOINTS =====

    @GetMapping("/reference-source/{token}")
    public ResponseEntity<byte[]> getReferenceImage(@PathVariable String token) {
        try {
            logger.info("Fetching reference image for token: {}", token);
            Path path = Paths.get("./uploads/references/", token + ".png");

            if (Files.exists(path)) {
                byte[] imageData = Files.readAllBytes(path);
                return ResponseEntity.ok()
                    .header("Content-Type", "image/png")
                    .header("Cache-Control", "public, max-age=3600")
                    .body(imageData);
            }

            logger.warn("Reference image not found for token: {}", token);
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            logger.error("Failed to get reference image for token: {}", token, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ===== HEALTH CHECK =====

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "AI Image Generation",
            "provider", "Pollinations.ai (free)",
            "timestamp", System.currentTimeMillis()
        ));
    }

    // ===== DTO CLASSES =====

    public static class AiImageRequest {
        private String prompt;
        private String draftId;
        private List<String> referenceImages = new ArrayList<>();
        private Integer count;

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }

        public String getDraftId() { return draftId; }
        public void setDraftId(String draftId) { this.draftId = draftId; }

        public List<String> getReferenceImages() { return referenceImages; }
        public void setReferenceImages(List<String> referenceImages) {
            this.referenceImages = referenceImages != null ? referenceImages : new ArrayList<>();
        }

        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }

    public static class ComposeUploadedRequest {
        private String imageBase64;

        public String getImageBase64() { return imageBase64; }
        public void setImageBase64(String imageBase64) { this.imageBase64 = imageBase64; }
    }
}