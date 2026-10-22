package com.example.backend.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.ChangeOrder;

@Repository
public interface ChangeOrderRepository extends JpaRepository<ChangeOrder, Long> {

    List<ChangeOrder> findByProjectNameOrderByIdAsc(String projectName);

    /**
     * Change orders for MANY projects in a single query (used by the public
     * share view, which is polled repeatedly, so it must not issue one query
     * per project).
     */
    List<ChangeOrder> findByProjectNameInOrderByIdAsc(Collection<String> projectNames);

    /** Renames a project across all change orders that reference it (no client column on this table). */
    @Modifying
    @Transactional
    @Query("UPDATE ChangeOrder c SET c.projectName = :newName WHERE c.projectName = :oldName")
    int renameProject(@Param("oldName") String oldName, @Param("newName") String newName);
}