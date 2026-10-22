package com.example.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
     * Finds the most recent message (either direction) for an RFI.
     * Used to anchor In-Reply-To/References headers when resubmitting.
     */
    Optional<RfiEmailMessage> findFirstByRfiIdOrderByMessageDateDesc(Long rfiId);
}