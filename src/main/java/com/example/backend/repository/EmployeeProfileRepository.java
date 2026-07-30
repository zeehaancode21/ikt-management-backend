package com.example.backend.repository;

import com.example.backend.dto.EmployeeJoinDateProjection;
import com.example.backend.entity.EmployeeProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {
    Optional<EmployeeProfile> findByUsername(String username);

    // Batch-resolves dateOfJoining for a set of usernames in a single
    // query — backs LeaveController.buildLeaveSummaries(), which needs
    // every employee's join date (to work out their annual leave limit via
    // LeavePolicy.leaveLimitFor) without loading each full EmployeeProfile
    // one at a time.
    @Query("SELECT e.username AS username, e.dateOfJoining AS dateOfJoining " +
           "FROM EmployeeProfile e WHERE e.username IN :usernames")
    List<EmployeeJoinDateProjection> findJoinDatesByUsernames(@Param("usernames") List<String> usernames);
}