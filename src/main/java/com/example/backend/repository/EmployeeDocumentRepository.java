package com.example.backend.repository;

import com.example.backend.entity.DocumentType;
import com.example.backend.entity.EmployeeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmployeeDocumentRepository extends JpaRepository<EmployeeDocument, Long> {
    List<EmployeeDocument> findByEmployeeUsername(String employeeUsername);
    Optional<EmployeeDocument> findByEmployeeUsernameAndDocType(String employeeUsername, DocumentType docType);

   List<EmployeeDocument> findByEmployeeUsernameIn(List<String> employeeUsernames);
}