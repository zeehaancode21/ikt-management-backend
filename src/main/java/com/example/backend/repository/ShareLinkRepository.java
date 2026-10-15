package com.example.backend.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.ShareLink;
import com.example.backend.entity.ShareScope;

@Repository
public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    boolean existsByToken(String token);

    /** Active links for one client (used by the owner's Share dialog). */
    List<ShareLink> findByScopeAndClientAndActiveTrueOrderByCreatedAtDesc(ShareScope scope, String client);

    /** Active links for one project (used by the owner's Share dialog). */
    List<ShareLink> findByScopeAndProjectIdAndActiveTrueOrderByCreatedAtDesc(ShareScope scope, Long projectId);

    /**
     * Records "last viewed" with a single tiny UPDATE (no entity is loaded).
     * Called by PublicShareService at most once every few minutes per link.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ShareLink l SET l.lastViewedAt = :at WHERE l.id = :id")
    int touchLastViewed(@Param("id") Long id, @Param("at") Instant at);
}