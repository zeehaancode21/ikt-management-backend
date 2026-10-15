package com.example.backend.dto;

import java.time.LocalDateTime;

/** One folder row WITHOUT its documents/sub-folder collections (which are EAGER on the entity). */
public interface FolderRowView {
    Long getId();
    String getName();
    String getDescription();
    String getCreatedBy();
    LocalDateTime getCreatedAt();
    Long getParentId();
}