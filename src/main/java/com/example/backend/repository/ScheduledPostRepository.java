package com.example.backend.repository;

import com.example.backend.entity.ScheduledPost;
import com.example.backend.entity.ScheduledPostStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledPostRepository extends JpaRepository<ScheduledPost, Long> {

    List<ScheduledPost> findByCreatedByOrderByScheduledForDesc(String createdBy);

    List<ScheduledPost> findByStatusAndScheduledForLessThanEqual(
            ScheduledPostStatus status, LocalDateTime now);

    List<ScheduledPost> findBySeriesIdOrderByScheduledForDesc(String seriesId);
}