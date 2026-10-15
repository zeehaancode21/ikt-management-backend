package com.example.backend.dto;

import java.time.LocalDate;

/**
 * Profile fields shown on the profile page, WITHOUT the profile picture bytes
 * (loading the EmployeeProfile entity always pulls the picture, up to 5 MB).
 */
public interface ProfileSummaryView {
    String getFullName();
    String getEmail();
    LocalDate getDateOfBirth();
    LocalDate getDateOfJoining();
    String getMobileNo();
    String getCurrentAddress();
    String getRoleName();
}