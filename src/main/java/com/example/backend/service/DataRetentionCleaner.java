package com.example.backend.service;

import com.example.backend.entity.GroupMessage;
import com.example.backend.entity.Message;
import com.example.backend.entity.Notification;
import com.example.backend.repository.GroupMessageRepository;
import com.example.backend.repository.MessageRepository;
import com.example.backend.repository.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Deletes messages, group messages, and notifications older than a configured
 * retention window. Runs on a daily schedule (configurable via cron property).
 *
 * Deletes happen in small batches, each in its own transaction, so a large
 * backlog doesn't hold one giant transaction open or lock tables for a long
 * time. Deletion goes through the entity (repository.deleteAll(...)) rather
 * than a bulk JPQL DELETE, because Message/GroupMessage/Notification all have
 * @ManyToMany attachments — deleting the entity lets Hibernate clean up the
 * join-table rows first. A bulk DELETE would skip that and risk a foreign
 * key violation (or orphaned join rows, if the FK isn't enforced).
 */
@Component
public class DataRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionCleaner.class);
    private static final int BATCH_SIZE = 500;

    private final MessageRepository messageRepository;
    private final GroupMessageRepository groupMessageRepository;
    private final NotificationRepository notificationRepository;

    @Value("${app.retention.messages-days:90}")
    private int messageRetentionDays;

    @Value("${app.retention.group-messages-days:90}")
    private int groupMessageRetentionDays;

    @Value("${app.retention.notifications-days:90}")
    private int notificationRetentionDays;

    public DataRetentionCleaner(MessageRepository messageRepository,
                                 GroupMessageRepository groupMessageRepository,
                                 NotificationRepository notificationRepository) {
        this.messageRepository = messageRepository;
        this.groupMessageRepository = groupMessageRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Runs daily at 03:00 server time by default. Override with
     * app.retention.cron in application.properties, e.g. to run hourly
     * for testing: app.retention.cron=0 0 * * * *
     */
    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}")
    public void purgeOldData() {
        log.info("Data retention cleanup started (messages>{}d, groupMessages>{}d, notifications>{}d)",
                messageRetentionDays, groupMessageRetentionDays, notificationRetentionDays);

        int deletedMessages = purgeOldMessages();
        int deletedGroupMessages = purgeOldGroupMessages();
        int deletedNotifications = purgeOldNotifications();

        log.info("Data retention cleanup finished: {} messages, {} group messages, {} notifications deleted",
                deletedMessages, deletedGroupMessages, deletedNotifications);
    }

    private int purgeOldMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(messageRetentionDays);
        int totalDeleted = 0;
        List<Message> batch;
        do {
            batch = messageRepository.findBySentAtBefore(cutoff, firstPage());
            if (!batch.isEmpty()) {
                deleteMessageBatch(batch);
                totalDeleted += batch.size();
            }
        } while (batch.size() == BATCH_SIZE);
        return totalDeleted;
    }

    private int purgeOldGroupMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(groupMessageRetentionDays);
        int totalDeleted = 0;
        List<GroupMessage> batch;
        do {
            batch = groupMessageRepository.findBySentAtBefore(cutoff, firstPage());
            if (!batch.isEmpty()) {
                deleteGroupMessageBatch(batch);
                totalDeleted += batch.size();
            }
        } while (batch.size() == BATCH_SIZE);
        return totalDeleted;
    }

    private int purgeOldNotifications() {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(notificationRetentionDays));
        int totalDeleted = 0;
        List<Notification> batch;
        do {
            batch = notificationRepository.findByCreatedAtBefore(cutoff, firstPage());
            if (!batch.isEmpty()) {
                deleteNotificationBatch(batch);
                totalDeleted += batch.size();
            }
        } while (batch.size() == BATCH_SIZE);
        return totalDeleted;
    }

    // Note: repository.deleteAll(batch) is already transactional on its own
    // (Spring Data's SimpleJpaRepository wraps it in a transaction per call),
    // so each batch commits independently without any extra @Transactional
    // here. Adding @Transactional to a method called from elsewhere in this
    // same class wouldn't actually apply anyway (self-invocation bypasses
    // Spring's proxy) — so we rely on the repository's own transaction boundary.
    private void deleteMessageBatch(List<Message> batch) {
        messageRepository.deleteAll(batch);
    }

    private void deleteGroupMessageBatch(List<GroupMessage> batch) {
        groupMessageRepository.deleteAll(batch);
    }

    private void deleteNotificationBatch(List<Notification> batch) {
        notificationRepository.deleteAll(batch);
    }

    // Always page 0: after each batch is deleted, the next "oldest 500" simply
    // becomes page 0 again, since the previous oldest rows are already gone.
    private Pageable firstPage() {
        return PageRequest.of(0, BATCH_SIZE);
    }
}