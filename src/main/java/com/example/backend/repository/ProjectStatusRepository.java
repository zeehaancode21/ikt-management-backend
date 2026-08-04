package com.example.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.dto.ClientYearOption;
import com.example.backend.dto.ProjectYearOption;
import com.example.backend.entity.ProjectStatus;

@Repository
public interface ProjectStatusRepository extends JpaRepository<ProjectStatus, Long> {

    Optional<ProjectStatus> findProjectStatusById(Long id);

    Optional<ProjectStatus> findByJobNumber(String jobNumber);

    Optional<ProjectStatus> findByProjectName(String projectName);

    List<ProjectStatus> findByClient(String client);

    List<ProjectStatus> findByYear(String year);

    List<ProjectStatus> findByClientAndYear(String client, String year);

    boolean existsByJobNumber(String jobNumber);

    boolean existsByProjectName(String projectName);

    void deleteByJobNumber(String jobNumber);

    @Query("SELECT DISTINCT p.client FROM ProjectStatus p")
    List<String> getAllClients();

    /**
     * All distinct (year, client) pairs across every project, newest year
     * first and alphabetical within a year. Backs the year-grouped
     * "Client" list (e.g. the Owner's Hours Dashboard's Manage Clients
     * popup), reusing the same grouping shape as
     * {@link #getProjectsByClientGroupedByYear(String)} so a client stays
     * reachable under every year it has activity in.
     */
    @Query("SELECT DISTINCT p.year AS year, p.client AS clientName FROM ProjectStatus p " +
            "ORDER BY p.year DESC, p.client ASC")
    List<ClientYearOption> getAllClientsGroupedByYear();

    @Query("SELECT DISTINCT p.projectName FROM ProjectStatus p WHERE p.client = :client")
    List<String> getProjectsByClients(String client);

    /**
     * All distinct (year, projectName) pairs for a client, newest year
     * first and alphabetical within a year. Backs the year-grouped
     * "Projects" dropdown so every project a client has ever had — across
     * every year — stays reachable in one list instead of being filtered
     * down to whichever year happens to be selected elsewhere in the UI.
     */
    @Query("SELECT DISTINCT p.year AS year, p.projectName AS projectName FROM ProjectStatus p " +
            "WHERE p.client = :client ORDER BY p.year DESC, p.projectName ASC")
    List<ProjectYearOption> getProjectsByClientGroupedByYear(@Param("client") String client);

    /** Renames a project across all project-status rows that reference it, scoped by client. */
    @Modifying
@Transactional
@Query("UPDATE ProjectStatus p SET p.projectName = :newName WHERE p.projectName = :oldName")
int renameProject(@Param("oldName") String oldName, @Param("newName") String newName);
}