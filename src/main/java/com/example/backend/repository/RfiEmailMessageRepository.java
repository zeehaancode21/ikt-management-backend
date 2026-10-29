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
     * True when this RFI already has an email with that RFC Message-ID header.
     * Stops the owner's Gmail sync from importing a second copy of a reply the
     * app already stored when a CC person sent it from their own Gmail.
     */
    boolean existsByRfiIdAndRfcMessageId(Long rfiId, String rfcMessageId);

    /**
     * Finds the most recent message (either direction) for an RFI.
     * Used to anchor In-Reply-To/References headers when resubmitting.
     */
    Optional<RfiEmailMessage> findFirstByRfiIdOrderByMessageDateDesc(Long rfiId);
}