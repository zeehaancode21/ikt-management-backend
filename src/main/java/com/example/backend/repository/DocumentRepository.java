package com.example.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.Document;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findAllByOrderByUploadedAtDesc();

    List<Document> findByProjectNameOrderByUploadedAtDesc(String projectName);

    List<Document> findByFolder_IdOrderByUploadedAtDesc(Long folderId);

    List<Document> findByFolder_IdAndUploadedByOrderByUploadedAtDesc(Long folderId, String uploadedBy);

    @Modifying
    @Transactional
    void deleteByUploadedBy(String username);

    /** Renames a project across all documents that reference it (no client column on this table). */
    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.projectName = :newName WHERE d.projectName = :oldName")
    int renameProject(@Param("oldName") String oldName, @Param("newName") String newName);
}