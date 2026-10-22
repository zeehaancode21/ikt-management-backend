// AttachmentRepository.java
package com.example.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.Attachment;


public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Attachment a WHERE a.uploadedBy = :username")
    void deleteAllByUploadedBy(@Param("username") String username);
}