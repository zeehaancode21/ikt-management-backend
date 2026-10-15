package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Preview of what an RFI's generated email subject/body will look like if
 * created right now, WITHOUT persisting anything or consuming an RFI number.
 *
 * Used by the "New RFI" form so the user can see (and optionally edit) the
 * default email body before actually sending — the same template
 * createAndSendRfi falls back to when no custom body is supplied.
 *
 * Note: rfiNumber/generatedEmailSubject here are a preview only. The number
 * actually assigned on submit could differ if another RFI is created for the
 * same project in between (rare, and harmless — the body the user sees/edits
 * is what gets sent regardless of the final number).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RfiEmailPreviewDto {

    private String generatedEmailSubject;

    private String emailBody;
}