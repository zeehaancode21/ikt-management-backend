package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Tracks, per (group, user) pair, the id of the newest group message that
 * user has read. Used to compute the unread indicator for group chats,
 * mirroring the readByReceiver flag already used for 1:1 messages.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(
    name = "group_read_status",
    uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "username"})
)
public class GroupReadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false)
    private String username;

    /** Id of the newest GroupMessage this user has seen (0 = none yet). */
    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId = 0L;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;
}