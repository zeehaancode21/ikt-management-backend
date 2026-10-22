package com.example.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.dto.FolderRowView;
import com.example.backend.entity.Folder;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {

    // /** Check duplicate name within the same parent (null = root level). */
    boolean existsByNameAndCreatedByAndParentId(String name, String createdBy, Long parentId);

    /** Root-level duplicate check (parentId IS NULL). */
    boolean existsByNameAndCreatedByAndParentIsNull(String name, String createdBy);

    @Modifying
    @Transactional
    void deleteByCreatedBy(String username);

    /**
     * Owner of a folder WITHOUT loading the Folder entity. Folder.documents and
     * Folder.subFolders are EAGER, so findById() drags in every document of the folder,
     * file bytes included, just to read one string.
     */
    @Query("select f.createdBy from Folder f where f.id = :id")
    Optional<String> findCreatedByById(@Param("id") Long id);

    // ── Tree reads that never touch the EAGER documents / subFolders collections ──
    String ROW_SELECT =
            "select f.id as id, f.name as name, f.description as description, f.createdBy as createdBy, "
          + "f.createdAt as createdAt, f.parentId as parentId from Folder f ";

    /** Same rows and order as findAllByCreatedByAndParentIsNullOrderByCreatedAtDesc, without the collections. */
    @Query(ROW_SELECT + "where f.createdBy = :createdBy and f.parentId is null order by f.createdAt desc")
    List<FolderRowView> findRootRows(@Param("createdBy") String createdBy);

    @Query(ROW_SELECT + "where f.parentId in :parentIds order by f.id")
    List<FolderRowView> findChildRows(@Param("parentIds") Collection<Long> parentIds);

    @Query(ROW_SELECT + "where f.id = :id")
    Optional<FolderRowView> findRowById(@Param("id") Long id);

    /** Rename/describe without loading (and re-saving) the folder's documents and their file bytes. */
    @Modifying
    @Transactional
    @Query("update Folder f set f.name = :name, f.description = :description where f.id = :id")
    int updateNameAndDescription(@Param("id") Long id, @Param("name") String name,
                                 @Param("description") String description);

    /** Bulk delete of folder rows. Callers must delete children before parents (self-referencing FK). */
    @Modifying
    @Transactional
    @Query("delete from Folder f where f.id in :ids")
    int deleteAllByIdsIn(@Param("ids") Collection<Long> ids);
}