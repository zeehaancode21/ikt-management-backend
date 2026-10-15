package com.example.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.backend.entity.UnmatchedEmail;

/**
 * Repository for UnmatchedEmail entities.
 * Manages emails that couldn't be automatically matched to an RFI.
 */
@Repository
public interface UnmatchedEmailRepository extends JpaRepository<UnmatchedEmail, Long> {

    /**
     * Finds an unmatched email by Gmail thread ID.
     * Used to check if an ambiguous thread has already been logged.
     */
    Optional<UnmatchedEmail> findByGmailThreadId(String gmailThreadId);

    /**
     * Finds all unresolved (unmatched) emails.
     * Used for admin review/dashboard.
     */
    List<UnmatchedEmail> findByResolvedOrderByCreatedAtDesc(Boolean resolved);

    /**
     * Finds unresolved emails for a specific project (if candidate was recorded).
     */
    List<UnmatchedEmail> findByCandidateProjectNameAndResolvedOrderByCreatedAtDesc(
        String projectName, Boolean resolved);

    /**
     * Counts how many unresolved unmatched emails exist.
     */
    long countByResolved(Boolean resolved);
}