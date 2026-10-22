package com.example.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.example.backend.entity.Rfi;
import com.example.backend.entity.RfiDiscipline;
import com.example.backend.entity.RfiPriority;
import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiTeamType;

/**
 * Read-only, column-limited queries for the public RFI share page.
 *
 * Why a separate repository: the normal RfiRepository returns full {@link Rfi}
 * entities, which drag in the email body (TEXT) and an attachment JOIN for every
 * row. The public page needs none of that (and must never expose it), so these
 * queries select ONLY the handful of columns the client is allowed to see.
 * That keeps each refresh small for the Railway database and Render's heap.
 *
 * Extends the bare Repository marker (no save/delete methods exist at all).
 */
public interface RfiShareReadRepository extends Repository<Rfi, Long> {

    /** The only RFI columns the public page can ever read. */
    interface RfiRow {
        Long getId();
        String getProjectName();
        String getRfiNumber();
        String getSubject();
        RfiDiscipline getDiscipline();
        RfiPriority getPriority();
        RfiStatus getStatus();
        RfiTeamType getTeamType();
        LocalDateTime getCreatedAt();
        LocalDateTime getResponseReceivedAt();
    }

    /** All RFIs of a project for one team type (newest first), capped by the Pageable. */
    @Query("SELECT r.id AS id, r.projectName AS projectName, r.rfiNumber AS rfiNumber, "
            + "r.subject AS subject, r.discipline AS discipline, r.priority AS priority, "
            + "r.status AS status, r.teamType AS teamType, r.createdAt AS createdAt, "
            + "r.responseReceivedAt AS responseReceivedAt "
            + "FROM Rfi r WHERE r.projectName = :projectName AND r.teamType = :teamType "
            + "ORDER BY r.id DESC")
    List<RfiRow> findByProject(@Param("projectName") String projectName,
                               @Param("teamType") RfiTeamType teamType,
                               Pageable pageable);

    @Query("SELECT r.id AS id, r.projectName AS projectName, r.rfiNumber AS rfiNumber, "
            + "r.subject AS subject, r.discipline AS discipline, r.priority AS priority, "
            + "r.status AS status, r.teamType AS teamType, r.createdAt AS createdAt, "
            + "r.responseReceivedAt AS responseReceivedAt "
            + "FROM Rfi r WHERE r.id = :id")
    Optional<RfiRow> findRowById(@Param("id") Long id);
}
