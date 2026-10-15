package com.example.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.backend.entity.RfiEmailMessage;

/**
 * Repository for RfiEmailMessage entities.
 * Handles fetching email messages, checking for duplicates, and building communication history.
 */
@Repository
public interface RfiEmailMessageRepository extends JpaRepository<RfiEmailMessage, Long> {

    /**
     * Fetches all email messages for an RFI, ordered by date (oldest first).
     */
    List<RfiEmailMessage> findByRfiIdOrderByMessageDateAsc(Long rfiId);

    /**
     * Checks if an email message with the given Gmail message ID already exists.
     * Used to prevent duplicate imports from multiple syncs.
     */
    boolean existsByGmailMessageId(String gmailMessageId);

    /**
     * Finds an email message by its Gmail message ID.
     * Returns empty if not found.
     */
    Optional<RfiEmailMessage> findByGmailMessageId(String gmailMessageId);

    /**
     * Finds all email messages for a Gmail thread.
     * Useful for verifying thread completeness or searching by thread.
     */
    List<RfiEmailMessage> findByGmailThreadIdOrderByMessageDateAsc(String gmailThreadId);

    /**
     * Counts how many messages exist for an RFI.
     */
    long countByRfiId(Long rfiId);

    /**
     * Finds the most recent incoming reply for an RFI (if any).
     * Used to determine the latest response timestamp.
     */
    @Query("SELECT e FROM RfiEmailMessage e WHERE e.rfi.id = :rfiId " +
           "AND e.direction = 'INCOMING' ORDER BY e.messageDate DESC LIMIT 1")
    Optional<RfiEmailMessage> findLatestIncomingReplyForRfi(@Param("rfiId") Long rfiId);

    /**
     * Finds the most recent message (either direction) for an RFI.
     * Used to anchor In-Reply-To/References headers when resubmitting.
     */
    Optional<RfiEmailMessage> findFirstByRfiIdOrderByMessageDateDesc(Long rfiId);
}