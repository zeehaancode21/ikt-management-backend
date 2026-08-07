package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Powers the Media Hub's "Generate with AI" image flow, where the fixed
 * company post template is always applied automatically.
 *
 * User prompt -> fixed template -> AI image generation -> final image
 * using that template, per the Media Hub requirement: the prompt controls
 * WHAT is generated, the template controls HOW/WHERE it is presented.
 *
 * This intentionally reuses {@link AiImageGenerationService} (the exact
 * same Pollinations.ai integration, rate limiting and concurrency guard
 * used elsewhere) rather than standing up a separate provider integration.
 * The user's prompt is sent to the model exactly as typed — nothing is
 * prepended, appended, or substituted — so what gets generated is strictly
 * what the user asked for. The only thing this class adds on top of that
 * is compositing the resulting content into the fixed template via
 * {@link TemplateImageComposerService}, so the header/footer/branding are
 * guaranteed untouched (see that class for why compositing is used instead
 * of asking the model to redraw the whole post).
 *
 * Applying the company template is not optional and does not depend on any
 * user input/toggle — every image produced through this service is always
 * composed into the fixed template before being returned.
 */
@Slf4j
@Service
public class TemplatedImageGenerationService {

    private static final int MAX_IMAGES_PER_REQUEST = 4;

    private final AiImageGenerationService aiImageGenerationService;
    private final TemplateImageComposerService templateImageComposerService;

    public TemplatedImageGenerationService(AiImageGenerationService aiImageGenerationService,
                                            TemplateImageComposerService templateImageComposerService) {
        this.aiImageGenerationService = aiImageGenerationService;
        this.templateImageComposerService = templateImageComposerService;
    }

    /**
     * The bundled company template ships with the app and is loaded at
     * startup (see TemplateImageComposerService) — it is not something
     * that can be unavailable at runtime, so this always returns true on
     * a running instance. Kept for callers that want an explicit check.
     */
    public boolean isTemplateAvailable() {
        return templateImageComposerService.isTemplateAvailable();
    }

    /**
     * Generates `count` finished, template-composed images for the given
     * user prompt. Each call generates its own content graphic (different
     * seed) so multiple options are meaningfully different, then composites
     * every one into a fresh copy of the fixed template.
     */
    public List<byte[]> generateTemplatedImages(String userPrompt, int count) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            throw new AiImageGenerationService.AiImageGenerationException(
                "Prompt is required to generate an image");
        }

        int requestedCount = Math.max(1, Math.min(count, MAX_IMAGES_PER_REQUEST));
        // Sent to the AI exactly as the user typed it — no hardcoded prefix,
        // suffix, or replacement prompt. Only the output *size* is steered
        // (to exactly fill the template's content area), which is a purely
        // technical parameter, not prompt content.
        String exactUserPrompt = userPrompt.trim();

        // One provider call per requested option, sized to exactly fill the
        // template's content area — no separate cropping/fitting step
        // needed. Reuses the same rate-limited/single-flight generation
        // path as the standard Media Hub flow.
        List<byte[]> composed = new ArrayList<>();
        for (int i = 0; i < requestedCount; i++) {
            List<byte[]> contentImages = aiImageGenerationService.generateImages(
                exactUserPrompt,
                List.of(),
                1,
                TemplateImageComposerService.CONTENT_WIDTH,
                TemplateImageComposerService.CONTENT_HEIGHT
            );
            byte[] content = contentImages.get(0);
            composed.add(templateImageComposerService.composeWithTemplate(content));
        }

        log.info("Generated {} templated image(s) for prompt: {}", composed.size(), userPrompt);
        return composed;
    }
}