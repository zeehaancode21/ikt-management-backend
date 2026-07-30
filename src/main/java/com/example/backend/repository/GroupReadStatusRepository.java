package com.example.backend.repository;

import com.example.backend.entity.GroupReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupReadStatusRepository extends JpaRepository<GroupReadStatus, Long> {

    Optional<GroupReadStatus> findByGroupIdAndUsername(Long groupId, String username);

    List<GroupReadStatus> findByUsername(String username);
}