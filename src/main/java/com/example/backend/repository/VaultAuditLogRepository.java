package com.example.backend.repository;

import com.example.backend.entity.VaultAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VaultAuditLogRepository extends JpaRepository<VaultAuditLog, Long> {
    List<VaultAuditLog> findTop200ByOrderByTimestampDesc();
}