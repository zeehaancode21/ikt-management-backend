// AttachmentDataRepository.java
package com.example.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.AttachmentData;

public interface AttachmentDataRepository extends JpaRepository<AttachmentData, Long> {

    /** The stored file bytes for one attachment (empty if none are stored). */
    Optional<AttachmentData> findByAttachment_Id(Long attachmentId);

    /** Cheap existence check that does NOT load the (possibly 20 MB) blob. */
    @Query("SELECT COUNT(d) > 0 FROM AttachmentData d WHERE d.attachment.id = :attachmentId")
    boolean existsForAttachment(@Param("attachmentId") Long attachmentId);

    /** Removes the stored bytes of one attachment (used once the file is read from Gmail instead). */
    @Modifying
    @Transactional
    @Query("DELETE FROM AttachmentData d WHERE d.attachment.id = :attachmentId")
    int deleteByAttachmentId(@Param("attachmentId") Long attachmentId);
}