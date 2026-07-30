package com.example.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    @Query("SELECT DISTINCT p.projectName FROM ProjectStatus p WHERE p.client = :client")
    List<String> getProjectsByClients(String client);

    /** Renames a project across all project-status rows that reference it, scoped by client. */
    @Modifying
@Transactional
@Query("UPDATE ProjectStatus p SET p.projectName = :newName WHERE p.projectName = :oldName")
int renameProject(@Param("oldName") String oldName, @Param("newName") String newName);
}