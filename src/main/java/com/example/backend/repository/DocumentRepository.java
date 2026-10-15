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

import com.example.backend.dto.DocumentMetaView;
import com.example.backend.entity.Document;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    // ── Metadata-only reads (never touch the file_data column) ───────────────
    // "left join" keeps documents that are not in any folder.
    String META_SELECT =
            "select d.id as id, d.fileName as fileName, d.originalFileName as originalFileName, "
          + "d.fileType as fileType, d.fileSize as fileSize, d.description as description, "
          + "d.category as category, d.projectName as projectName, d.uploadedBy as uploadedBy, "
          + "d.uploadStatus as uploadStatus, d.uploadedAt as uploadedAt, f.id as folderId "
          + "from Document d left join d.folder f ";

    @Query(META_SELECT + "order by d.uploadedAt desc")
    List<DocumentMetaView> findAllMeta();

    @Query(META_SELECT + "where d.projectName = :projectName order by d.uploadedAt desc")
    List<DocumentMetaView> findMetaByProjectName(@Param("projectName") String projectName);

    @Query(META_SELECT + "where f.id = :folderId order by d.uploadedAt desc")
    List<DocumentMetaView> findMetaByFolderId(@Param("folderId") Long folderId);

    @Query(META_SELECT + "where d.id = :id")
    Optional<DocumentMetaView> findMetaById(@Param("id") Long id);

    /** Documents of many folders in ONE query (used to build the folder tree). */
    @Query(META_SELECT + "where f.id in :folderIds order by d.id")
    List<DocumentMetaView> findMetaByFolderIds(@Param("folderIds") Collection<Long> folderIds);

    /** True if the folder already holds files (the "leaf" rule), without loading them. */
    boolean existsByFolder_Id(Long folderId);

    /** Deletes every document in the given folders with ONE statement (no file bytes loaded). */
    @Modifying
    @Transactional
    @Query("delete from Document d where d.folder.id in :folderIds")
    int deleteAllInFolders(@Param("folderIds") Collection<Long> folderIds);

    /** True once the file bytes have been stored (checked in the DB, bytes not transferred). */
    @Query("select count(d) from Document d where d.id = :id and d.fileData is not null")
    long countWithFileData(@Param("id") Long id);

    // ── Writes that do not load or rewrite the file bytes ────────────────────
    @Modifying
    @Transactional
    @Query("update Document d set d.description = :description, d.category = :category, "
         + "d.projectName = :projectName where d.id = :id")
    int updateMeta(@Param("id") Long id, @Param("description") String description,
                   @Param("category") String category, @Param("projectName") String projectName);

    @Modifying
    @Transactional
    @Query("update Document d set d.description = :description where d.id = :id")
    int updateDescription(@Param("id") Long id, @Param("description") String description);

    /** deleteById() loads the entity (and its blob) first; this is a single DELETE. */
    @Modifying
    @Transactional
    @Query("delete from Document d where d.id = :id")
    int deleteDocumentById(@Param("id") Long id);

    @Modifying
    @Transactional
    void deleteByUploadedBy(String username);

    /** Renames a project across all documents that reference it (no client column on this table). */
    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.projectName = :newName WHERE d.projectName = :oldName")
    int renameProject(@Param("oldName") String oldName, @Param("newName") String newName);
}