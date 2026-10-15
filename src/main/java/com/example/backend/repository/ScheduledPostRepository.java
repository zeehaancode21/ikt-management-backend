package com.example.backend.repository;

import com.example.backend.entity.ScheduledPost;
import com.example.backend.entity.ScheduledPostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledPostRepository extends JpaRepository<ScheduledPost, Long> {

    List<ScheduledPost> findByCreatedByOrderByScheduledForDesc(String createdBy);

    List<ScheduledPost> findByStatusAndScheduledForLessThanEqual(
            ScheduledPostStatus status, LocalDateTime now);

    List<ScheduledPost> findBySeriesIdOrderByScheduledForDesc(String seriesId);

    // Atomically flips PENDING -> RUNNING only if it's still PENDING.
    // Returns 1 if this call won the claim, 0 if another instance/thread
    // already claimed it first. Prevents double-execution races.
    @Modifying
    @Transactional
    @Query("UPDATE ScheduledPost p SET p.status = com.example.backend.entity.ScheduledPostStatus.RUNNING " +
           "WHERE p.id = :id AND p.status = com.example.backend.entity.ScheduledPostStatus.PENDING")
    int claim(@Param("id") Long id);
}