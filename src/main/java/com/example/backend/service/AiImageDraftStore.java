package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the AI-generated image options for each in-progress post draft, and
 * which one (if any) has been finalized as the post's attachment.
 *
 * This is intentionally an in-memory, short-lived store (same pattern as
 * ReferenceImageCacheService) — draft image options only need to survive for
 * the length of a single "generate -> pick -> publish" session, not across
 * server restarts.
 */
@Slf4j
@Service
public class AiImageDraftStore {

    private static final long TTL_MILLIS = 60 * 60 * 1000L; // 1 hour

    public record GeneratedImageOption(int id, String base64, boolean selected) {
    }

    private record DraftState(List<GeneratedImageOption> options, Integer selectedImageId, long expiresAtEpochMs) {
    }

    private final Map<String, DraftState> drafts = new ConcurrentHashMap<>();
    private final Map<Integer, String> imageIdToDraftId = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);

    /**
     * Stores a freshly generated batch of image options for a draft
     * (replacing any previous batch, but keeping the current selection if
     * one was already made) and returns the options with selection flags applied.
     */
    public List<GeneratedImageOption> saveGenerated(String draftId, List<byte[]> images, String mimeType) {
        List<GeneratedImageOption> options = new ArrayList<>();
        for (byte[] image : images) {
            int id = idSequence.getAndIncrement();
            String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image);
            options.add(new GeneratedImageOption(id, dataUrl, false));
            imageIdToDraftId.put(id, draftId);
        }

        DraftState previous = drafts.get(draftId);
        Integer previousSelection = previous == null ? null : previous.selectedImageId();
        drafts.put(draftId, new DraftState(options, previousSelection, expiryFromNow()));
        return applySelection(draftId);
    }

    /** Returns the current options for a draft (empty list if none/expired). */
    public List<GeneratedImageOption> getOptions(String draftId) {
        DraftState state = getIfPresent(draftId);
        return state == null ? Collections.emptyList() : state.options();
    }

    /** Returns the currently selected image id for a draft, or null if none selected. */
    public Integer getSelectedImageId(String draftId) {
        DraftState state = getIfPresent(draftId);
        return state == null ? null : state.selectedImageId();
    }

    /**
     * Marks a previously generated image as the finalized selection for its
     * draft. Returns the selected option, or null if the id is unknown or expired.
     */
    public GeneratedImageOption select(int imageId) {
        String draftId = imageIdToDraftId.get(imageId);
        if (draftId == null) {
            return null;
        }

        DraftState state = getIfPresent(draftId);
        if (state == null) {
            return null;
        }

        boolean exists = state.options().stream().anyMatch(o -> o.id() == imageId);
        if (!exists) {
            return null;
        }

        drafts.put(draftId, new DraftState(state.options(), imageId, state.expiresAtEpochMs()));
        List<GeneratedImageOption> updated = applySelection(draftId);
        return updated.stream().filter(o -> o.id() == imageId).findFirst().orElse(null);
    }

    /** Removes all generated options and selection state for a draft (e.g. on delete/publish). */
    public void clear(String draftId) {
        DraftState state = drafts.remove(draftId);
        if (state != null) {
            state.options().forEach(o -> imageIdToDraftId.remove(o.id()));
        }
    }

    private List<GeneratedImageOption> applySelection(String draftId) {
        DraftState state = drafts.get(draftId);
        if (state == null) {
            return Collections.emptyList();
        }
        List<GeneratedImageOption> withSelection = new ArrayList<>();
        for (GeneratedImageOption o : state.options()) {
            boolean isSelected = state.selectedImageId() != null && o.id() == state.selectedImageId();
            withSelection.add(new GeneratedImageOption(o.id(), o.base64(), isSelected));
        }
        drafts.put(draftId, new DraftState(withSelection, state.selectedImageId(), state.expiresAtEpochMs()));
        return withSelection;
    }

    private long expiryFromNow() {
        return Instant.now().toEpochMilli() + TTL_MILLIS;
    }

    private DraftState getIfPresent(String draftId) {
        DraftState state = drafts.get(draftId);
        if (state == null) {
            return null;
        }
        if (state.expiresAtEpochMs() < Instant.now().toEpochMilli()) {
            clear(draftId);
            return null;
        }
        return state;
    }

    /** Periodically sweeps expired drafts so the store doesn't grow unbounded. */
    @Scheduled(fixedRate = 15 * 60 * 1000L)
    public void purgeExpired() {
        long now = Instant.now().toEpochMilli();
        List<String> expired = new ArrayList<>();
        drafts.forEach((draftId, state) -> {
            if (state.expiresAtEpochMs() < now) {
                expired.add(draftId);
            }
        });
        expired.forEach(this::clear);
        if (!expired.isEmpty()) {
            log.debug("Purged {} expired AI image drafts", expired.size());
        }
    }
}