package com.example.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
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

    /**
     * Ids (ascending, after {@code afterId}) of imported/sent Gmail messages that still have at
     * least one attachment which is NOT yet read from Gmail. Used by GmailAttachmentOffloadService.
     */
    @Query("select m.id from RfiEmailMessage m "
         + "where m.gmailMessageId is not null and m.id > :afterId "
         + "and exists (select x.id from m.attachments x where x.gmailMessageId is null) "
         + "order by m.id")
    List<Long> findIdsWithStoredAttachmentsAfter(@Param("afterId") Long afterId, Pageable pageable);

    /** Username that sent the RFI this message belongs to (= owner of the mailbox the message sits in). */
    @Query("select m.rfi.sentBy from RfiEmailMessage m where m.id = :id")
    Optional<String> findSentByOfMessage(@Param("id") Long id);
}