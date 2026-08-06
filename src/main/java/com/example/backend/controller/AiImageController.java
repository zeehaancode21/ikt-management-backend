package com.example.backend.controller;

import com.example.backend.service.AiImageDraftStore;
import com.example.backend.service.AiImageDraftStore.GeneratedImageOption;
import com.example.backend.service.AiImageGenerationService;
import com.example.backend.service.AiImageGenerationService.AiImageGenerationException;
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
    private AiImageGenerationService aiImageGenerationService;

    @Autowired
    private AiImageDraftStore aiImageDraftStore;

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

    // ===== IMAGE GENERATION ENDPOINTS =====

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generateImage(@RequestBody AiImageRequest request) {
        try {
            long startTime = System.currentTimeMillis();

            String draftId = request.getDraftId();
            if (draftId == null || draftId.equals("undefined") || draftId.equals("null")) {
                draftId = UUID.randomUUID().toString();
                logger.info("Generated new draft ID: {}", draftId);
            }

            logger.info("Generating image(s) for draft: {} with prompt: {}", draftId, request.getPrompt());

            if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Prompt is required"
                ));
            }

            int count = request.getCount() != null && request.getCount() > 0 ? request.getCount() : defaultCount;
            final String finalDraftId = draftId;

            CompletableFuture<List<byte[]>> future = CompletableFuture.supplyAsync(() ->
                aiImageGenerationService.generateImages(
                    request.getPrompt(),
                    request.getReferenceImages(),
                    count
                )
            );

            List<byte[]> generatedImages;
            try {
                generatedImages = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                logger.warn("Image generation timed out after {}ms", timeoutMs);
                future.cancel(true);
                return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                    .body(Map.of(
                        "success", false,
                        "message", "Image generation is taking too long. Please try again."
                    ));
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof AiImageGenerationException) {
                    logger.warn("Image generation failed for draft {}: {}", finalDraftId, cause.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                            "success", false,
                            "message", cause.getMessage()
                        ));
                }
                logger.error("Image generation failed", cause);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                        "success", false,
                        "message", "Failed to generate image: " + cause.getMessage()
                    ));
            }

           List<GeneratedImageOption> options = aiImageDraftStore.saveGenerated(finalDraftId, generatedImages, "image/jpeg");
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Generated {} image(s) in {}ms for draft {}", options.size(), duration, finalDraftId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "images", options,
                "draftId", finalDraftId,
                "count", options.size(),
                "duration", duration,
                "message", "Generated " + options.size() + " image option" + (options.size() == 1 ? "" : "s") + " successfully"
            ));

        } catch (AiImageGenerationException e) {
            logger.warn("AI image generation rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                    "success", false,
                    "message", e.getMessage()
                ));
        } catch (Exception e) {
            logger.error("AI image generation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", "Failed to generate image: " + e.getMessage()
                ));
        }
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> generateImageForm(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "draftId", required = false) String draftId,
            @RequestParam(value = "referenceImages", required = false) String referenceImages,
            @RequestParam(value = "count", required = false) Integer count) {

        AiImageRequest request = new AiImageRequest();
        request.setPrompt(prompt);
        request.setDraftId(draftId);
        request.setCount(count);

        if (referenceImages != null && !referenceImages.isEmpty()) {
            List<String> refs = Arrays.asList(referenceImages.split(","));
            request.setReferenceImages(refs);
        }

        return generateImage(request);
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
}