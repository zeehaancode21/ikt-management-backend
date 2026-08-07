package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Composites AI-generated content into the ONE fixed IK Tangience LinkedIn
 * post template, instead of asking an image-generation model to redraw the
 * whole post (logo, header, footer, CONNECT NOW block included).
 *
 * Why compositing instead of image-guided ("kontext") generation:
 * Pollinations' image-guided mode still repaints the entire image from
 * scratch using the reference only as style guidance — it does not
 * guarantee any pixel of the source is preserved exactly. That is not
 * acceptable here: the logo, "IK Tangience" wordmark, "We Speak Fluent
 * Steel" slogan, footer, CONNECT NOW block, phone number and email must be
 * byte-for-byte identical on every generated post. So instead:
 *
 *   1. The template PNG below is loaded once and never modified.
 *   2. AI generation (see TemplatedImageGenerationService) is asked to
 *      produce ONLY the interior content graphic, sized to exactly fill
 *      the template's content-area rectangle.
 *   3. That content is drawn into a fresh copy of the template at the
 *      fixed CONTENT_* coordinates below. Every pixel outside that
 *      rectangle — header, footer, logo, slogan, CONNECT NOW, phone,
 *      email — comes straight from the template file and is never
 *      touched.
 *
 * The content-area rectangle was measured directly from the provided
 * template (1080x1080): the white card spans roughly x:36-1045,
 * y:168-909, with a rounded border. CONTENT_* below insets that by ~20px
 * so generated content never overlaps the border.
 *
 * Where the template file lives:
 * The company template ships as a bundled application asset at
 * src/main/resources/public/template.png. Spring Boot automatically
 * serves everything under classpath:/public/ as static web content, so
 * this same file is also reachable at GET /template.png with zero extra
 * configuration — one file, one source of truth, used both for
 * server-side compositing (below) and as the public asset path. There is
 * no database row or external API involved in resolving the template —
 * it is a static file shipped with the app, so it is always present in
 * any correctly built deployment.
 */
@Slf4j
@Service
public class TemplateImageComposerService {

    private static final String TEMPLATE_CLASSPATH_LOCATION = "public/template.png";

    /** Content-area rectangle, in the template's own 1080x1080 pixel space. */
    public static final int CONTENT_X = 56;
    public static final int CONTENT_Y = 188;
    public static final int CONTENT_WIDTH = 968;
    public static final int CONTENT_HEIGHT = 702;

    private volatile BufferedImage cachedTemplate;

    /**
     * Loads the bundled template at startup. This intentionally fails
     * application startup (rather than degrading to a runtime "template
     * unavailable" state) if the asset is missing or unreadable — a
     * bundled file either ships correctly with the build or the build is
     * broken, and that should be caught at deploy time, not surfaced to
     * end users as a vague "contact an administrator" error.
     */
    @PostConstruct
    public void loadTemplate() {
        try (InputStream in = new ClassPathResource(TEMPLATE_CLASSPATH_LOCATION).getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IllegalStateException("Template image could not be decoded");
            }
            this.cachedTemplate = img;
            log.info("Loaded company post template ({}x{}) from {}",
                img.getWidth(), img.getHeight(), TEMPLATE_CLASSPATH_LOCATION);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Could not load the bundled company post template from classpath:" + TEMPLATE_CLASSPATH_LOCATION +
                    " — check that src/main/resources/public/template.png exists in the build.", e);
        }
    }

    /**
     * The bundled template is always available once the application has
     * started successfully (loadTemplate() above fails startup otherwise).
     * Kept as a cheap sanity check for callers, not as a "maybe missing"
     * runtime toggle.
     */
    public boolean isTemplateAvailable() {
        return cachedTemplate != null;
    }

    /**
     * Draws AI-generated content (PNG/JPEG bytes, any size) into the fixed
     * content area of a fresh copy of the template, and returns the
     * composed image as PNG bytes. The template itself is never mutated —
     * a new BufferedImage copy is created on every call.
     */
    public byte[] composeWithTemplate(byte[] contentImageBytes) {
        BufferedImage template = cachedTemplate;
        if (template == null) {
            // Should be unreachable: loadTemplate() fails startup if the
            // bundled asset can't be loaded, so a running instance always
            // has it cached. Guarded defensively rather than silently
            // proceeding with a null template.
            throw new IllegalStateException("Company post template was not loaded at startup");
        }
        if (contentImageBytes == null || contentImageBytes.length == 0) {
            throw new ImageCompositionException("Generated content image was empty");
        }

        BufferedImage content;
        try {
            content = ImageIO.read(new ByteArrayInputStream(contentImageBytes));
        } catch (Exception e) {
            throw new ImageCompositionException("Generated content image could not be read: " + e.getMessage());
        }
        if (content == null) {
            throw new ImageCompositionException("Generated content image could not be decoded");
        }

        BufferedImage composed = new BufferedImage(template.getWidth(), template.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composed.createGraphics();
        try {
            // 1. Fixed header/footer/branding — an exact copy of the template.
            g.drawImage(template, 0, 0, null);

            // 2. AI content, scaled (never cropped/stretched out of aspect —
            //    generation already requests CONTENT_WIDTH x CONTENT_HEIGHT,
            //    this resize is just a safety net if the provider returns a
            //    slightly different size) to exactly fill the content area.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(content, CONTENT_X, CONTENT_Y, CONTENT_WIDTH, CONTENT_HEIGHT, null);
        } finally {
            g.dispose();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(composed, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ImageCompositionException("Failed to encode composed image: " + e.getMessage());
        }
    }

    public static class ImageCompositionException extends RuntimeException {
        public ImageCompositionException(String message) {
            super(message);
        }
    }
}