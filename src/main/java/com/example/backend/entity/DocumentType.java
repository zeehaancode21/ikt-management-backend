package com.example.backend.entity;

/**
 * The fixed set of file-based confidential documents every employee must submit.
 * Bank details are handled separately (structured fields, see EmployeeBankDetail)
 * since they were requested as text fields rather than an uploaded file.
 */
public enum DocumentType {
    PAN_CARD,
    AADHAAR_CARD,
    TENTH_MARKSHEET,
    PU_MARKSHEET
}