package com.example.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Only unread notifications for a specific user
     */
    @Query("SELECT DISTINCT n FROM Notification n LEFT JOIN FETCH n.attachments WHERE n.targetUsername = :username AND n.read = false ORDER BY n.createdAt DESC")
    List<Notification> findUnreadForUser(@Param("username") String username);

    /**
     * All notifications (read + unread) for a specific user
     */
    @Query("SELECT DISTINCT n FROM Notification n LEFT JOIN FETCH n.attachments WHERE n.targetUsername = :username ORDER BY n.createdAt DESC")
    List<Notification> findAllForUser(@Param("username") String username);

    @Query("SELECT DISTINCT n FROM Notification n LEFT JOIN FETCH n.attachments WHERE n.targetUsername = :username AND n.type = 'BROADCAST' ORDER BY n.createdAt DESC")
    List<Notification> findAnnouncementsForUser(@Param("username") String username);

    /**
     * Unread count for a user
     */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.targetUsername = :username AND n.read = false")
    long countUnreadForUser(@Param("username") String username);

    /**
     * Unread counts for a user, grouped by notification type. Each row is
     * [type, count] — used to build per-module sidebar badges (Messages,
     * Leave Portal, Permission Portal, etc.) without fetching full rows.
     */
    @Query("SELECT n.type, COUNT(n) FROM Notification n WHERE n.targetUsername = :username AND n.read = false GROUP BY n.type")
    List<Object[]> countUnreadForUserGroupedByType(@Param("username") String username);

    /**
     * Marks every unread notification of the given types as read for a
     * user — e.g. clearing the sidebar badge for a module once the user
     * opens it, without touching notifications from other modules.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.read = true WHERE n.targetUsername = :username AND n.type IN :types AND n.read = false")
    int markReadForUserAndTypes(@Param("username") String username, @Param("types") List<String> types);

    @Modifying
    @Transactional
    @Query("DELETE FROM Notification n WHERE n.targetUsername = :username")
    int deleteAllNotificationsForUser(@Param("username") String username);

    /**
     * Batched lookup of old notifications for the retention cleanup job (see
     * MessageRepository for why entities, not bulk DELETE).
     */
    List<Notification> findByCreatedAtBefore(java.time.Instant cutoff, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.read = true WHERE n.id = :id AND n.targetUsername = :username")
    int markReadForUser(@Param("id") Long id, @Param("username") String username);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.read = true WHERE n.targetUsername = :username AND n.read = false")
    int markAllReadForUser(@Param("username") String username);

    /** Delete a single notification for a user (ownership-checked in the query itself) */
@Modifying
@Transactional
@Query("DELETE FROM Notification n WHERE n.id = :id AND n.targetUsername = :username")
int deleteByIdForUser(@Param("id") Long id, @Param("username") String username);


/** Delete all BROADCAST notifications for a user */
@Modifying
@Transactional
@Query("DELETE FROM Notification n WHERE n.targetUsername = :username AND n.type = 'BROADCAST'")
int deleteAllAnnouncementsForUser(@Param("username") String username);
}