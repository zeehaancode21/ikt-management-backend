package com.example.backend.repository;

import com.example.backend.entity.GeneratedImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, Long> {

    /**
     * Find all images for a draft, ordered by creation time
     */
    List<GeneratedImage> findByDraftIdAndCreatedByOrderByCreatedAtAsc(String draftId, String createdBy);

    /**
     * Find a specific image by ID and owner
     */
    Optional<GeneratedImage> findByIdAndCreatedBy(Long id, String createdBy);

    /**
     * Find the selected image for a draft
     */
    Optional<GeneratedImage> findFirstByDraftIdAndCreatedByAndSelectedTrue(String draftId, String createdBy);

    /**
     * Clear the selection flag for all images in a draft
     */
    @Modifying
    @Transactional
    @Query("UPDATE GeneratedImage g SET g.selected = false WHERE g.draftId = :draftId AND g.createdBy = :createdBy")
    void clearSelectionForDraft(@Param("draftId") String draftId, @Param("createdBy") String createdBy);

    /**
     * Delete all images for a draft
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM GeneratedImage g WHERE g.draftId = :draftId AND g.createdBy = :createdBy")
    void deleteByDraftIdAndCreatedBy(@Param("draftId") String draftId, @Param("createdBy") String createdBy);

    /**
     * Count images for a draft
     */
    long countByDraftIdAndCreatedBy(String draftId, String createdBy);

    /**
     * Check if a draft has any images
     */
    boolean existsByDraftIdAndCreatedBy(String draftId, String createdBy);

    /**
     * Find all images for a draft (regardless of owner) - for admin use
     */
    List<GeneratedImage> findByDraftId(String draftId);

    /**
     * Delete all images for a draft (admin use)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM GeneratedImage g WHERE g.draftId = :draftId")
    void deleteByDraftId(@Param("draftId") String draftId);

    /**
     * Find images older than a specific date - useful for cleanup
     */
    List<GeneratedImage> findByCreatedAtBefore(LocalDateTime dateTime);

    /**
     * Delete images older than a specific date
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM GeneratedImage g WHERE g.createdAt < :cutoffDate")
    void deleteOldImages(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Count images created by a user
     */
    long countByCreatedBy(String createdBy);

    /**
     * Find all images created by a user
     */
    List<GeneratedImage> findByCreatedBy(String createdBy);

    /**
     * Find all selected images for a user
     */
    List<GeneratedImage> findByCreatedByAndSelectedTrue(String createdBy);

    /**
     * Count selected images for a draft
     */
    @Query("SELECT COUNT(g) FROM GeneratedImage g WHERE g.draftId = :draftId AND g.createdBy = :createdBy AND g.selected = true")
    long countSelectedByDraftAndUser(@Param("draftId") String draftId, @Param("createdBy") String createdBy);

    /**
     * Bulk delete images for multiple drafts (admin use)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM GeneratedImage g WHERE g.draftId IN :draftIds")
    void deleteByDraftIds(@Param("draftIds") List<String> draftIds);

    /**
     * FIXED: Find images by prompt text (partial match)
     * Using CAST to handle CLOB to STRING conversion
     */
    @Query("SELECT g FROM GeneratedImage g WHERE LOWER(CAST(g.prompt AS string)) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<GeneratedImage> searchByPrompt(@Param("searchTerm") String searchTerm);

    /**
     * Alternative search method using native query (works with TEXT/CLOB)
     */
    @Query(value = "SELECT * FROM generated_image WHERE LOWER(prompt) LIKE LOWER(CONCAT('%', :searchTerm, '%'))", 
           nativeQuery = true)
    List<GeneratedImage> searchByPromptNative(@Param("searchTerm") String searchTerm);

    /**
     * Get all images for a draft with their selection status
     * Ordered by selected status first (selected images first), then by creation date
     */
    @Query("SELECT g FROM GeneratedImage g WHERE g.draftId = :draftId AND g.createdBy = :createdBy ORDER BY g.selected DESC, g.createdAt ASC")
    List<GeneratedImage> findByDraftIdAndCreatedByOrderBySelectedDescCreatedAtAsc(@Param("draftId") String draftId, @Param("createdBy") String createdBy);

    /**
     * Get statistics for a user
     */
    @Query("SELECT COUNT(g) FROM GeneratedImage g WHERE g.createdBy = :createdBy AND g.createdAt BETWEEN :startDate AND :endDate")
    long countByUserAndDateRange(@Param("createdBy") String createdBy, 
                                 @Param("startDate") LocalDateTime startDate, 
                                 @Param("endDate") LocalDateTime endDate);

    /**
     * Find unused images (not selected) older than a specific time
     */
    @Query("SELECT g FROM GeneratedImage g WHERE g.selected = false AND g.createdAt < :cutoffDate AND g.createdBy = :createdBy")
    List<GeneratedImage> findUnusedImagesOlderThan(@Param("createdBy") String createdBy, 
                                                    @Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Count images by content type (for analytics)
     */
    @Query("SELECT g.contentType, COUNT(g) FROM GeneratedImage g GROUP BY g.contentType")
    List<Object[]> countByContentType();

    /**
     * Get the latest generated image for a draft
     */
    Optional<GeneratedImage> findFirstByDraftIdAndCreatedByOrderByCreatedAtDesc(String draftId, String createdBy);
}