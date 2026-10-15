// AttachmentDataRepository.java
package com.example.backend.repository;

import com.example.backend.entity.AttachmentData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AttachmentDataRepository extends JpaRepository<AttachmentData, Long> {

    /** The stored file bytes for one attachment (empty if none are stored). */
    Optional<AttachmentData> findByAttachment_Id(Long attachmentId);
}