package com.example.backend.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.example.backend.entity.RfiStatus;
import com.example.backend.entity.RfiStatusHistory;

/**
 * Column-limited status-history query for the public RFI share page.
 *
 * Deliberately does NOT select {@code changedBy} (a username / an outside
 * person's email) or {@code note} (can contain email addresses) - the client
 * only sees "status went from A to B at time T".
 */
public interface RfiShareHistoryReadRepository extends Repository<RfiStatusHistory, Long> {

    interface HistoryRow {
        Long getRfiId();
        RfiStatus getOldStatus();
        RfiStatus getNewStatus();
        LocalDateTime getChangedAt();
    }

    /** Newest first; the Pageable caps the total rows read in one refresh. */
    @Query("SELECT h.rfi.id AS rfiId, h.oldStatus AS oldStatus, h.newStatus AS newStatus, "
            + "h.changedAt AS changedAt "
            + "FROM RfiStatusHistory h WHERE h.rfi.id IN :rfiIds "
            + "ORDER BY h.changedAt DESC, h.id DESC")
    List<HistoryRow> findForRfis(@Param("rfiIds") Collection<Long> rfiIds, Pageable pageable);
}
