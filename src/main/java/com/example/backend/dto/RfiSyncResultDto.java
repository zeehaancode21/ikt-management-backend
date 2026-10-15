package com.example.backend.dto;

import com.example.backend.entity.Rfi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for a Gmail sync operation on a single RFI.
 * Returned by RfiGmailSyncService.syncRfi and included in batch results.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfiSyncResultDto {

    /**
     * The RFI's database ID.
     */
    private Long rfiId;

    /**
     * The RFI number (e.g. "RFI#001").
     */
    private String rfiNumber;

    /**
     * True if the sync succeeded; false if an error occurred.
     */
    private Boolean success;

    /**
     * Number of new email messages imported during this sync.
     */
    private Integer newMessagesCount;

    /**
     * Error message (if success == false). Null if success == true.
     */
    private String errorMessage;

    /**
     * The updated RFI entity after the sync.
     */
    private Rfi updatedRfi;
}