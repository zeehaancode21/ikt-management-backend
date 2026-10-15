package com.example.backend.repository;

import com.example.backend.dto.EmployeeDocumentMeta;
import com.example.backend.entity.DocumentType;
import com.example.backend.entity.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, Long> {
    List<EmployeeDocument> findByEmployeeUsername(String employeeUsername);
    Optional<EmployeeDocument> findByEmployeeUsernameAndDocType(String employeeUsername, DocumentType docType);

   List<EmployeeDocument> findByEmployeeUsernameIn(List<String> employeeUsernames);

    // Metadata-only reads: never select encrypted_file_data.
    String META_SELECT =
            "select d.id as id, d.employeeUsername as employeeUsername, d.docType as docType, "
          + "d.originalFileName as originalFileName, d.contentType as contentType, d.uploadedAt as uploadedAt "
          + "from EmployeeDocument d ";

    @Query(META_SELECT + "where d.employeeUsername = :username")
    List<EmployeeDocumentMeta> findMetaByEmployeeUsername(@Param("username") String username);

    @Query(META_SELECT + "where d.employeeUsername in :usernames")
    List<EmployeeDocumentMeta> findMetaByEmployeeUsernameIn(@Param("usernames") List<String> usernames);
}