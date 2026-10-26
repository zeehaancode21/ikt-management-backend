// package com.example.backend.repository;

// import java.util.List;

// @Repository
// public interface RfiRepository extends JpaRepository<Rfi, Long> {

//     List<Rfi> findByProjectNameOrderByIdAsc(String projectName);

package com.example.backend.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiDiscipline;
import com.example.backend.entity.RfiPriority;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiTeamType;

@Repository
public interface RfiRepository extends JpaRepository<Rfi, Long> {

    // Rfi.attachment is EAGER. Without the entity graph Hibernate loads the RFIs in one query
    // and then runs ONE extra "select ... from attachments where id=?" PER RFI (N+1).
    // The entity graph pulls the attachment in with a single JOIN instead.
    @EntityGraph(attributePaths = "attachment")
    List<Rfi> findByProjectNameOrderByIdAsc(String projectName);

    /** Null teamType/discipline/priority means "don't filter on that field" — backs the ALL option in every filter. */
    @EntityGraph(attributePaths = "attachment")
    @Query("SELECT r FROM Rfi r WHERE r.projectName = :projectName " +
           "AND (:teamType IS NULL OR r.teamType = :teamType) " +
           "AND (:discipline IS NULL OR r.discipline = :discipline) " +
           "AND (:priority IS NULL OR r.priority = :priority) " +
           "ORDER BY r.id DESC")
    List<Rfi> findByProjectNameWithFilters(
        @Param("projectName") String projectName,
        @Param("teamType") RfiTeamType teamType,
        @Param("discipline") RfiDiscipline discipline,
        @Param("priority") RfiPriority priority
    );

    /**
     * Counts the number of RFIs for a given project.
     * Used to generate the next sequential RFI number.
     */
    long countByProjectName(String projectName);

    /** All RFI numbers (e.g. "RFI#001") already issued for a project — used to find the highest one. */
    @Query("SELECT r.rfiNumber FROM Rfi r WHERE r.projectName = :projectName")
    List<String> findRfiNumbersByProjectName(@Param("projectName") String projectName);

    // ------------------------------------------------------------------
    // Auto reply check — tiny projections only (no attachment join, no
    // full entity) so the periodic "any new replies?" pass stays cheap.
    // ------------------------------------------------------------------

    interface WatchedRfiRow {
        Long getId();
        String getGmailThreadId();
    }

    /** One sender's RFIs that could still receive a reply (thread known, status not excluded). */
    @Query("SELECT r.id AS id, r.gmailThreadId AS gmailThreadId FROM Rfi r " +
           "WHERE TRIM(r.sentBy) = :sentBy AND r.gmailThreadId IS NOT NULL AND r.status NOT IN :excluded")
    List<WatchedRfiRow> findWatchedBySender(@Param("sentBy") String sentBy,
                                            @Param("excluded") Collection<RfiStatus> excluded);

    /** Every sender that currently has at least one watched RFI (used by the optional background check). */
    @Query("SELECT DISTINCT TRIM(r.sentBy) FROM Rfi r " +
           "WHERE r.sentBy IS NOT NULL AND r.gmailThreadId IS NOT NULL AND r.status NOT IN :excluded")
    List<String> findSendersWithWatchedRfis(@Param("excluded") Collection<RfiStatus> excluded);
}