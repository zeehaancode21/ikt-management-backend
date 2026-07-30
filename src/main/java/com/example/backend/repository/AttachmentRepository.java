// AttachmentRepository.java
package com.example.backend.repository;

import com.example.backend.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByUploadedBy(String uploadedBy);

    @Modifying
    @Transactional
    @Query("DELETE FROM Attachment a WHERE a.uploadedBy = :username")
    void deleteAllByUploadedBy(@Param("username") String username);

    @Query("SELECT a FROM Attachment a WHERE a.id IN :ids")
    List<Attachment> findAllByIds(@Param("ids") List<Long> ids);
}