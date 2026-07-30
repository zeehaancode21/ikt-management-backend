package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_groups")
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Username of the person who created the group */
    @Column(nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * Comma-separated list of member usernames (simple, no join table needed).
     * Stored as TEXT so it can hold many members.
     */
    @Column(columnDefinition = "TEXT")
    private String members; // e.g. "alice,bob,charlie"

    /**
     * Number of messages the current requesting user hasn't read yet.
     * Computed on demand in GroupController#listGroups, never persisted.
     */
    @Transient
    private Long unreadCount = 0L;

    // ── Convenience helpers ──────────────────────────────────────────────────

    public List<String> getMemberList() {
        if (members == null || members.isBlank()) return new ArrayList<>();
        List<String> list = new ArrayList<>();
        for (String m : members.split(",")) {
            String trimmed = m.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }

    public void setMemberList(List<String> list) {
        this.members = list == null ? "" : String.join(",", list);
    }

    public boolean hasMember(String username) {
        return getMemberList().contains(username);
    }
}