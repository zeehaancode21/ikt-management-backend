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

    private static final long TTL_MILLIS = 30 * 60 * 1000L; // 30 minutes

    // Total size of all stored images (base64 text) across ALL drafts. Without a cap, every
    // generated image sat in the heap for a full hour; on a 512 MB instance a burst of
    // generations could exhaust memory. When exceeded, the oldest OTHER drafts are dropped.
    private static final long MAX_TOTAL_CHARS = 32L * 1024 * 1024;

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
        // The replaced batch's ids used to stay in imageIdToDraftId forever (slow leak).
        DraftState replaced = drafts.get(draftId);
        if (replaced != null) {
            replaced.options().forEach(o -> imageIdToDraftId.remove(o.id()));
        }

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
        enforceMemoryBudget(draftId);
        return applySelection(draftId);
    }

    /** Drops the oldest other drafts until the total stored image size is back under the cap. */
    private void enforceMemoryBudget(String keepDraftId) {
        long total = 0;
        for (DraftState s : drafts.values()) {
            total += sizeOf(s);
        }
        while (total > MAX_TOTAL_CHARS) {
            String oldestId = null;
            long oldestExpiry = Long.MAX_VALUE;
            for (Map.Entry<String, DraftState> e : drafts.entrySet()) {
                if (e.getKey().equals(keepDraftId)) continue; // never drop the draft being saved
                if (e.getValue().expiresAtEpochMs() < oldestExpiry) {
                    oldestExpiry = e.getValue().expiresAtEpochMs();
                    oldestId = e.getKey();
                }
            }
            if (oldestId == null) break; // only the current draft is left
            DraftState victim = drafts.get(oldestId);
            if (victim != null) total -= sizeOf(victim);
            clear(oldestId);
            log.warn("AI image draft store over its memory cap; dropped the oldest draft {}", oldestId);
        }
    }

    private static long sizeOf(DraftState state) {
        long n = 0;
        for (GeneratedImageOption o : state.options()) {
            n += o.base64() == null ? 0 : o.base64().length();
        }
        return n;
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