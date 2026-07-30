package com.example.backend.repository;

import com.example.backend.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // Conversation between two users (both directions).
    // LEFT JOIN FETCH + DISTINCT loads each message's attachments in this same
    // query, instead of Hibernate lazily firing one extra SELECT per message
    // when the list gets serialized (the N+1 you saw in the logs).
    // Safe to fetch-join here because this method returns a full List, not a
    // Pageable result — fetch-joining a collection together with pagination
    // is what you want to avoid, not this.
    @Query("SELECT DISTINCT m FROM Message m LEFT JOIN FETCH m.attachments LEFT JOIN FETCH m.replyTo WHERE "
            + "(m.senderUsername = :user1 AND m.receiverUsername = :user2) OR "
            + "(m.senderUsername = :user2 AND m.receiverUsername = :user1) "
            + "ORDER BY m.sentAt ASC")
    List<Message> findConversation(@Param("user1") String user1, @Param("user2") String user2);

    // All messages received by a user (for unread count)
    List<Message> findByReceiverUsernameAndReadByReceiverFalse(String receiverUsername);

    // Paginated inbox query — left as lazy-loaded attachments (fetch-joining a
    // collection alongside Pageable would paginate in memory and corrupt the
    // page size/count). Relies on @BatchSize on Message.attachments, backed up
    // by hibernate.default_batch_fetch_size in application.properties.
    // LEFT JOIN FETCH m.replyTo is safe here even with Pageable — unlike
    // attachments (a collection, which would corrupt paging), replyTo is a
    // to-one relation and doesn't multiply row counts.
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.replyTo WHERE " +
           "m.senderUsername = :username OR m.receiverUsername = :username")
    Page<Message> findAllInvolving(@Param("username") String username, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM Message m WHERE m.senderUsername = :username OR m.receiverUsername = :username")
    int deleteAllMessagesByUsername(@Param("username") String username);

    // Bulk mark-as-read. Must run BEFORE findConversation() loads the rows in
    // the same request/transaction — call order matters (see MessageController).
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.readByReceiver = true " +
           "WHERE m.receiverUsername = :receiver AND m.senderUsername = :sender AND m.readByReceiver = false")
    int markAsRead(@Param("receiver") String receiver, @Param("sender") String sender);
    
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.replyTo WHERE " +
       "(m.senderUsername = :user1 AND m.receiverUsername = :user2) OR " +
       "(m.senderUsername = :user2 AND m.receiverUsername = :user1)")
    Page<Message> findConversationPage(@Param("user1") String user1, @Param("user2") String user2, Pageable pageable);

    /**
     * Batched lookup of old messages for the retention cleanup job.
     * Deliberately returns entities (not a bulk DELETE) so Hibernate removes
     * the message_attachments join-table rows correctly before the message
     * itself is deleted — a raw "DELETE FROM Message WHERE sentAt < ?" would
     * skip that and could violate the join table's foreign key.
     */
    List<Message> findBySentAtBefore(java.time.LocalDateTime cutoff, Pageable pageable);
}