package com.example.backend.service;

import com.example.backend.entity.RecurrenceType;
import com.example.backend.entity.ScheduledPost;
import com.example.backend.entity.ScheduledPostStatus;
import com.example.backend.repository.ScheduledPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduling layer on top of the existing AI Posting / Manual Prompting
 * feature. This service never re-implements content generation or LinkedIn
 * publishing — it only decides *when* to call the existing
 * {@link MistralService#generatePost(String, String)} and
 * {@link LinkedInService#postContent(String, String, String, String)}
 * methods, exactly as SocialHubController does today for on-demand posts.
 */
@Service
public class ScheduledPostService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPostService.class);

    @Autowired
    private ScheduledPostRepository scheduledPostRepository;

    @Autowired
    private MistralService mistralService;

    @Autowired
    private LinkedInService linkedInService;

    // Mirrors the built-in category -> topics list from the frontend
    // (SocialHub.tsx CATEGORIES). Used ONLY to pick a sensible default topic
    // when a scheduled post is created without a Category / Technical
    // Content Prompt, so the unattended run still goes through the exact
    // same generatePost(topic, categoryId) call an interactive AI-mode click
    // would have made. Editing the topics shown in the UI does not require
    // editing this list — it only affects the auto-picked fallback topic.
    private static final Map<String, List<String>> DEFAULT_TOPICS = new LinkedHashMap<>();
    static {
        DEFAULT_TOPICS.put("showcase", List.of(
                "Showcase a recently completed steel detailing project with key challenges and results.",
                "Write a LinkedIn post highlighting precision in structural steel detailing."));
        DEFAULT_TOPICS.put("insights", List.of(
                "Explain why accurate steel detailing reduces fabrication errors.",
                "Discuss the importance of BIM in modern steel detailing."));
        DEFAULT_TOPICS.put("technical", List.of(
                "Write about the importance of clash detection before fabrication.",
                "Explain the benefits of accurate GA drawings and shop drawings."));
        DEFAULT_TOPICS.put("branding", List.of(
                "Introduce our steel detailing company and our expertise.",
                "Write a post about our commitment to quality and accuracy."));
        DEFAULT_TOPICS.put("educational", List.of(
                "What is steel detailing and why is it important?",
                "Shop drawings vs. erection drawings: what's the difference?"));
        DEFAULT_TOPICS.put("client", List.of(
                "Explain how our detailing services help fabricators save time.",
                "Show how accurate detailing reduces costly site modifications."));
        DEFAULT_TOPICS.put("engagement", List.of(
                "What's the most challenging steel connection you've detailed?",
                "Which project phase benefits most from BIM coordination?"));
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public List<ScheduledPost> listForUser(String username) {
        return scheduledPostRepository.findByCreatedByOrderByScheduledForDesc(username);
    }

    public ScheduledPost getOwned(Long id, String username) {
        ScheduledPost post = scheduledPostRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheduled post not found."));
        requireOwner(post, username);
        return post;
    }

    private void requireOwner(ScheduledPost post, String username) {
        if (!post.getCreatedBy().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to access this scheduled post.");
        }
    }

    // ── Mutations ───────────────────────────────────────────────────────────

    public ScheduledPost create(String username, String categoryId, String customPrompt,
                                 LocalDateTime scheduledFor, RecurrenceType recurrence) {
        if (scheduledFor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledFor is required.");
        }
        if (scheduledFor.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledFor must be in the future.");
        }

        ScheduledPost post = new ScheduledPost();
        post.setCreatedBy(username);
        post.setCategoryId(blankToNull(categoryId));
        post.setCustomPrompt(blankToNull(customPrompt));
        post.setScheduledFor(scheduledFor);
        post.setStatus(ScheduledPostStatus.PENDING);
        post.setRecurrence(recurrence != null ? recurrence : RecurrenceType.NONE);
        // seriesId is auto-assigned in @PrePersist when left null — a fresh
        // series for a brand-new schedule.
        return scheduledPostRepository.save(post);
    }

    public ScheduledPost update(Long id, String username, String categoryId, String customPrompt,
                                 LocalDateTime scheduledFor, RecurrenceType recurrence) {
        ScheduledPost post = getOwned(id, username);
        if (post.getStatus() != ScheduledPostStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending scheduled posts can be edited.");
        }
        if (scheduledFor != null) {
            if (scheduledFor.isBefore(LocalDateTime.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledFor must be in the future.");
            }
            post.setScheduledFor(scheduledFor);
        }
        post.setCategoryId(blankToNull(categoryId));
        post.setCustomPrompt(blankToNull(customPrompt));
        if (recurrence != null) {
            post.setRecurrence(recurrence);
        }
        return scheduledPostRepository.save(post);
    }

    /** Full execution history for the recurring series this post belongs to. */
    public List<ScheduledPost> seriesHistory(Long id, String username) {
        ScheduledPost post = getOwned(id, username);
        return scheduledPostRepository.findBySeriesIdOrderByScheduledForDesc(post.getSeriesId());
    }

    public ScheduledPost cancel(Long id, String username) {
        ScheduledPost post = getOwned(id, username);
        if (post.getStatus() != ScheduledPostStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending scheduled posts can be cancelled.");
        }
        post.setStatus(ScheduledPostStatus.CANCELLED);
        return scheduledPostRepository.save(post);
    }

    public void delete(Long id, String username) {
        ScheduledPost post = getOwned(id, username);
        if (post.getStatus() == ScheduledPostStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete a scheduled post while it is running.");
        }
        scheduledPostRepository.delete(post);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ── Execution ───────────────────────────────────────────────────────────

    /**
     * Polls for due scheduled posts and runs them through the existing AI
     * posting workflow. Runs every 30 seconds; each due job is claimed
     * (moved to RUNNING) before generation starts so a slow run can't be
     * picked up twice by the next poll.
     */
    @Scheduled(fixedDelay = 30000)
    public void processDuePosts() {
        List<ScheduledPost> due = scheduledPostRepository
                .findByStatusAndScheduledForLessThanEqual(ScheduledPostStatus.PENDING, LocalDateTime.now());

        for (ScheduledPost post : due) {
            runOne(post.getId());
        }
    }

    private void runOne(Long id) {
        // Re-fetch and claim inside its own unit of work so one failing job
        // can't affect the others in the same poll.
        ScheduledPost post = scheduledPostRepository.findById(id).orElse(null);
        if (post == null || post.getStatus() != ScheduledPostStatus.PENDING) {
            return; // already claimed, cancelled, or deleted since the poll ran
        }
        post.setStatus(ScheduledPostStatus.RUNNING);
        scheduledPostRepository.save(post);

        String topic;
        try {
            // May fill in post.categoryId if none was supplied — read it
            // back afterward so generation uses whatever category (if any)
            // ends up associated with the resolved topic.
            topic = resolveTopic(post);
            String categoryId = post.getCategoryId();

            // Reuse the exact same generation call SocialHubController#generatePost makes.
            String content = mistralService.generatePost(topic, categoryId);

            // Reuse the exact same publish call SocialHubController#postToLinkedIn makes.
            // Passing null for the token makes LinkedInService fall back to the
            // server-configured linkedin.access.token, since there's no
            // interactive user session to supply one for an unattended run.
            String resultMessage = linkedInService.postContent(content, topic, null, null);

            post.setResolvedTopic(topic);
            post.setGeneratedContent(content);
            post.setPostResultMessage(resultMessage);
            post.setStatus(ScheduledPostStatus.COMPLETED);
            post.setExecutedAt(LocalDateTime.now());
            log.info("Scheduled post {} completed for user {}", post.getId(), post.getCreatedBy());
        } catch (Exception e) {
            log.error("Scheduled post {} failed: {}", post.getId(), e.getMessage(), e);
            post.setStatus(ScheduledPostStatus.FAILED);
            post.setFailureReason(e.getMessage() != null ? e.getMessage() : e.toString());
            post.setExecutedAt(LocalDateTime.now());
        }
        scheduledPostRepository.save(post);

        // For a recurring schedule, queue up the next occurrence now that
        // this one has run — regardless of whether it succeeded or failed,
        // the same way a weekly/monthly cron entry keeps firing on its own
        // cadence. Cancelling or deleting the *next* (still-PENDING)
        // occurrence is how a user stops the series.
        scheduleNextOccurrenceIfRecurring(post);
    }

    private void scheduleNextOccurrenceIfRecurring(ScheduledPost justRan) {
        RecurrenceType recurrence = justRan.getRecurrence();
        if (recurrence == null || recurrence == RecurrenceType.NONE) {
            return;
        }

        LocalDateTime next = switch (recurrence) {
            case WEEKLY -> justRan.getScheduledFor().plusWeeks(1);
            case MONTHLY -> justRan.getScheduledFor().plusMonths(1);
            default -> null;
        };
        if (next == null) {
            return;
        }

        ScheduledPost nextOccurrence = new ScheduledPost();
        nextOccurrence.setCreatedBy(justRan.getCreatedBy());
        nextOccurrence.setCategoryId(justRan.getCategoryId());
        nextOccurrence.setCustomPrompt(justRan.getCustomPrompt());
        nextOccurrence.setScheduledFor(next);
        nextOccurrence.setStatus(ScheduledPostStatus.PENDING);
        nextOccurrence.setRecurrence(recurrence);
        nextOccurrence.setSeriesId(justRan.getSeriesId());
        scheduledPostRepository.save(nextOccurrence);
        log.info("Queued next {} occurrence of series {} for {}",
                recurrence, justRan.getSeriesId(), next);
    }

    /**
     * Resolves the topic string to hand to MistralService.generatePost:
     *  - If a Technical Content Prompt was supplied, use it verbatim
     *    (identical to typing it in Manual Prompting mode).
     *  - Otherwise, if a Category was supplied, pick one of its built-in
     *    topics at random (identical to picking a topic in AI mode).
     *  - Otherwise, pick a random category and topic, so the unattended run
     *    still goes through the same generation flow a manual click would.
     */
    private String resolveTopic(ScheduledPost post) {
        if (post.getCustomPrompt() != null && !post.getCustomPrompt().isBlank()) {
            return post.getCustomPrompt();
        }

        String categoryId = post.getCategoryId();
        List<String> topics = (categoryId != null) ? DEFAULT_TOPICS.get(categoryId) : null;

        if (topics == null || topics.isEmpty()) {
            // No usable category, or an unrecognized one — fall back to a
            // random category from the default set.
            List<String> keys = List.copyOf(DEFAULT_TOPICS.keySet());
            String randomCategory = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
            post.setCategoryId(randomCategory);
            topics = DEFAULT_TOPICS.get(randomCategory);
        }

        return topics.get(ThreadLocalRandom.current().nextInt(topics.size()));
    }
}