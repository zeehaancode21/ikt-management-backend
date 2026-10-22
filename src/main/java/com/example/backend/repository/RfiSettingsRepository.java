package com.example.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.backend.entity.RfiSettings;

/**
 * RfiSettingsRepository.java
 * =====================================================================
 * Updated repository for the merged RfiSettings table. Email contacts
 * are now part of this entity, stored as JSON columns.
 * 
 * The old RfiEmailContactRepository is no longer needed since all
 * operations are now handled through RfiSettings.
 * =====================================================================
 */
@Repository
public interface RfiSettingsRepository extends JpaRepository<RfiSettings, Long> {
    
    /**
     * Find settings by user ID
     * This is the primary lookup method
     */
    Optional<RfiSettings> findByUserId(Long userId);
    
    /**
     * Check if settings exist for a user
     */
    boolean existsByUserId(Long userId);
    
    /**
     * Delete settings by user ID
     */
    void deleteByUserId(Long userId);
}