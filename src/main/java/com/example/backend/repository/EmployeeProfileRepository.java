package com.example.backend.repository;

import com.example.backend.dto.EmployeeJoinDateProjection;
import com.example.backend.dto.EmployeeRoleNameProjection;
import com.example.backend.entity.EmployeeProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {

    Optional<EmployeeProfile> findByUsername(String username);

    @Query("SELECT e.username AS username, e.dateOfJoining AS dateOfJoining "
            + "FROM EmployeeProfile e WHERE e.username IN :usernames")
    List<EmployeeJoinDateProjection> findJoinDatesByUsernames(@Param("usernames") List<String> usernames);

    @Query("SELECT e.username AS username, e.roleName AS roleName "
            + "FROM EmployeeProfile e WHERE e.username IN :usernames")
    List<EmployeeRoleNameProjection> findRoleNamesByUsernames(@Param("usernames") List<String> usernames);

}
