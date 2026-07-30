package com.example.backend.repository;

import com.example.backend.entity.Project;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("SELECT DISTINCT p.client FROM Project p")
    List<String> findByClient();

    @Query("SELECT DISTINCT p.projectName FROM Project p WHERE p.client = :client")
    List<String> findProjectNamesByClient(@Param("client") String client);
}
