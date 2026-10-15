package com.example.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.backend.entity.RfiStatusHistory;

/**
 * Repository for RfiStatusHistory entities.
 * Tracks all status changes on RFIs for audit and timeline purposes.
 */
@Repository
public interface RfiStatusHistoryRepository extends JpaRepository<RfiStatusHistory, Long> {

    /**
     * Fetches all status history for an RFI, ordered by timestamp (oldest first).
     */
    List<RfiStatusHistory> findByRfiIdOrderByChangedAtAsc(Long rfiId);

    /**
     * Fetches all status history for an RFI, ordered by timestamp (newest first).
     */
    List<RfiStatusHistory> findByRfiIdOrderByChangedAtDesc(Long rfiId);

    /**
     * Counts how many status changes an RFI has had.
     */
    long countByRfiId(Long rfiId);
}