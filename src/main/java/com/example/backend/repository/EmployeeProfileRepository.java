package com.example.backend.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.dto.EmployeeJoinDateProjection;
import com.example.backend.dto.EmployeeRoleNameProjection;
import com.example.backend.dto.ProfileSummaryView;
import com.example.backend.entity.EmployeeProfile;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {

    Optional<EmployeeProfile> findByUsername(String username);

    // ── Light reads: findByUsername() always loads the profile picture (up to 5 MB) ──
    @Query("select e.roleName from EmployeeProfile e where e.username = :username")
    Optional<String> findRoleNameByUsername(@Param("username") String username);

    @Query("select e.dateOfJoining from EmployeeProfile e where e.username = :username")
    Optional<LocalDate> findDateOfJoiningByUsername(@Param("username") String username);

    @Query("select e.fullName as fullName, e.email as email, e.dateOfBirth as dateOfBirth, "
         + "e.dateOfJoining as dateOfJoining, e.mobileNo as mobileNo, e.currentAddress as currentAddress, "
         + "e.roleName as roleName from EmployeeProfile e where e.username = :username")
    Optional<ProfileSummaryView> findSummaryByUsername(@Param("username") String username);

    /** 1 if this user has a picture, else 0 (checked in the DB; bytes not transferred). */
    @Query("select count(e) from EmployeeProfile e where e.username = :username and e.profilePicture is not null")
    long countWithPicture(@Param("username") String username);

    /**
     * Last-modified time of a user's profile, only if they have a picture (checked in the DB;
     * the picture bytes are NOT transferred). Used as the picture's ETag. Empty if there is no
     * picture or updatedAt is null, in which case the controller just serves the picture normally.
     */
    @Query("select e.updatedAt from EmployeeProfile e where e.username = :username and e.profilePicture is not null")
    Optional<LocalDateTime> findPictureVersion(@Param("username") String username);

    boolean existsByUsername(String username);

    // ── Writes that never load or rewrite the profile picture (up to 5 MB) ──────────────
    // updatedAt is set here because JPQL updates skip the entity's @PreUpdate callback.

    /** Same columns the old load-modify-save wrote from the profile form (nulls overwrite, as before). */
    @Modifying
    @Transactional
    @Query("update EmployeeProfile e set e.fullName = :fullName, e.email = :email, "
         + "e.dateOfBirth = :dateOfBirth, e.dateOfJoining = :dateOfJoining, e.mobileNo = :mobileNo, "
         + "e.currentAddress = :currentAddress, e.updatedBy = :updatedBy, e.updatedAt = :updatedAt "
         + "where e.username = :username")
    int updateProfileFields(@Param("username") String username, @Param("fullName") String fullName,
                            @Param("email") String email, @Param("dateOfBirth") LocalDate dateOfBirth,
                            @Param("dateOfJoining") LocalDate dateOfJoining, @Param("mobileNo") String mobileNo,
                            @Param("currentAddress") String currentAddress, @Param("updatedBy") String updatedBy,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying
    @Transactional
    @Query("update EmployeeProfile e set e.profilePicture = :picture, e.profilePictureContentType = :contentType, "
         + "e.updatedBy = :updatedBy, e.updatedAt = :updatedAt where e.username = :username")
    int updatePicture(@Param("username") String username, @Param("picture") byte[] picture,
                      @Param("contentType") String contentType, @Param("updatedBy") String updatedBy,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying
    @Transactional
    @Query("update EmployeeProfile e set e.roleName = :roleName, e.updatedAt = :updatedAt where e.username = :username")
    int updateRoleName(@Param("username") String username, @Param("roleName") String roleName,
                       @Param("updatedAt") LocalDateTime updatedAt);

    @Query("SELECT e.username AS username, e.dateOfJoining AS dateOfJoining "
            + "FROM EmployeeProfile e WHERE e.username IN :usernames")
    List<EmployeeJoinDateProjection> findJoinDatesByUsernames(@Param("usernames") List<String> usernames);

    @Query("SELECT e.username AS username, e.roleName AS roleName "
            + "FROM EmployeeProfile e WHERE e.username IN :usernames")
    List<EmployeeRoleNameProjection> findRoleNamesByUsernames(@Param("usernames") List<String> usernames);

}