package com.example.backend.repository;

import com.example.backend.entity.Folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {

    /** All root-level folders for a user (parent IS NULL). */
    List<Folder> findAllByCreatedByAndParentIsNullOrderByCreatedAtDesc(String createdBy);

    /** All folders for a user regardless of depth (for deletion clean-up etc.). */
    List<Folder> findAllByCreatedByOrderByCreatedAtDesc(String createdBy);

    /** All root-level folders regardless of owner. */
    List<Folder> findAllByOrderByCreatedAtDesc();

    /** Child folders of a given parent. */
    List<Folder> findAllByParent_IdOrderByCreatedAtDesc(Long parentId);

    /** Check duplicate name within the same parent (null = root level). */
    boolean existsByNameAndCreatedByAndParentId(String name, String createdBy, Long parentId);

    /** Root-level duplicate check (parentId IS NULL). */
    boolean existsByNameAndCreatedByAndParentIsNull(String name, String createdBy);

    boolean existsByNameAndCreatedBy(String name, String createdBy);

    boolean existsByName(String name);

    @Modifying
    @Transactional
    void deleteByCreatedBy(String username);
}