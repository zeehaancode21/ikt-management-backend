package com.example.backend.repository;

import com.example.backend.entity.OwnerTotpSecret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OwnerTotpSecretRepository extends JpaRepository<OwnerTotpSecret, Long> {
    Optional<OwnerTotpSecret> findByUsername(String username);
}