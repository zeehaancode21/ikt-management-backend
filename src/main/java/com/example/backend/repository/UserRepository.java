package com.example.backend.repository;

import com.example.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    // @Query("SELECT u.username FROM User u WHERE u.role = :role")
    // List<String> findAllEmployeeNames(@Param("role") String role);

    @Query("SELECT u.username FROM User u WHERE u.role IN :roles")
    List<String> findUsernamesByRoles(@Param("roles") List<String> roles);
}
