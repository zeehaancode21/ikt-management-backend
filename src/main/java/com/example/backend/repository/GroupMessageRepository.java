package com.example.backend.repository;

import com.example.backend.entity.GroupMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface GroupMessageRepository extends JpaRepository<GroupMessage, Long> {

    // LEFT JOIN FETCH + DISTINCT: loads each group message's attachments in
    // this same query instead of one extra SELECT per message when the full
    // history list gets serialized (GET /groups/{id}/messages). Safe here
    // because this returns a full List, not a Pageable result.
    @Query("SELECT DISTINCT gm FROM GroupMessage gm LEFT JOIN FETCH gm.attachments LEFT JOIN FETCH gm.replyTo " +
           "WHERE gm.group.id = :groupId ORDER BY gm.sentAt ASC")
    List<GroupMessage> findByGroup_IdOrderBySentAtAsc(@Param("groupId") Long groupId);

    @Modifying
    @Transactional
    @Query("DELETE FROM GroupMessage gm WHERE gm.group.id = :groupId")
    void deleteAllByGroupId(@Param("groupId") Long groupId);

    /** Batched lookup of old group messages for the retention cleanup job (see MessageRepository for why entities, not bulk DELETE). */
    List<GroupMessage> findBySentAtBefore(java.time.LocalDateTime cutoff, org.springframework.data.domain.Pageable pageable);

    /**
     * Count messages in this group, newer than the given id, that weren't
     * sent by the current user — i.e. the unread count for that user.
     */
    long countByGroup_IdAndIdGreaterThanAndSenderUsernameNot(Long groupId, Long id, String senderUsername);

    /** Highest message id currently in the group, used when marking it as read. */
    @Query("SELECT MAX(gm.id) FROM GroupMessage gm WHERE gm.group.id = :groupId")
    Long findMaxIdByGroupId(@Param("groupId") Long groupId);
}