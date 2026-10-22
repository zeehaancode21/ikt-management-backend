package com.example.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.backend.entity.Group;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    @Query("SELECT g FROM Group g WHERE g.createdBy = :username " +
       "OR g.members = :username " +
       "OR g.members LIKE CONCAT(:username, ',%') " +
       "OR g.members LIKE CONCAT('%,', :username, ',%') " +
       "OR g.members LIKE CONCAT('%,', :username)")
    List<Group> findGroupsForUser(@Param("username") String username);
}