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

import com.example.backend.entity.RfiShareLink;

@Repository
public interface RfiShareLinkRepository extends JpaRepository<RfiShareLink, Long> {

    Optional<RfiShareLink> findByToken(String token);

    boolean existsByToken(String token);

    /** Active links for one project (owner's Share dialog). */
    List<RfiShareLink> findByScopeAndProjectIdAndActiveTrueOrderByCreatedAtDesc(String scope, Long projectId);

    /** Active links for one RFI (owner's Share dialog). */
    List<RfiShareLink> findByScopeAndRfiIdAndActiveTrueOrderByCreatedAtDesc(String scope, Long rfiId);

    /**
     * Records "last viewed" with a single tiny UPDATE (no entity is loaded).
     * PublicRfiShareService calls it at most once every few minutes per link.
     */
    @Modifying
    @Transactional
    @Query("UPDATE RfiShareLink l SET l.lastViewedAt = :at WHERE l.id = :id")
    int touchLastViewed(@Param("id") Long id, @Param("at") Instant at);
}
