package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Basic employee profile info (name, DOB, mobile, address) and profile
 * picture. NOTE: unlike EmployeeDocument/EmployeeBankDetail, these fields
 * are stored in plain form (not AES-encrypted) because they need to be
 * readable broadly across the app (Messages, sidebar, profile page) without
 * the owner's vault unlock — they are personal info, but not in the same
 * sensitivity tier as PAN/Aadhaar/bank account numbers. If you want these
 * encrypted-at-rest too, this is the file to change (wrap each String field
 * the same way EmployeeBankDetail does, using EncryptionService).
 */
@Entity
@Table(name = "employee_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    private String fullName;
    private LocalDate dateOfBirth;

    // Date the employee joined the company. Drives the annual leave quota:
    // see LeavePolicy.leaveLimitFor(...) — employees with 3+ years of
    // service get the higher (24-day) limit, everyone else gets 18.
    private LocalDate dateOfJoining;

    private String mobileNo;

    @Column(length = 1000)
    private String currentAddress;

    @Lob
    @Column(name = "profile_picture", columnDefinition = "LONGBLOB")
    @JsonIgnore
    private byte[] profilePicture;
    
    private String email;  

    // Custom display title set by an admin via Employee Management, e.g.
    // "Senior Checker" or "QA Executive". Distinct from the system `role`
    // (User.role, e.g. USER/LEAD) which drives permissions — this field is
    // purely cosmetic and shown in place of the system role wherever the
    // employee's title is displayed (sidebar, profile). Falls back to the
    // system role when blank/null.
    private String roleName;

    @JsonIgnore
    private String profilePictureContentType;

    private String updatedBy;
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}