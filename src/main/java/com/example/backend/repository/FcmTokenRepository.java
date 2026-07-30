package com.example.backend.repository;

import com.example.backend.entity.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    // Get all tokens for a user (they may have multiple devices)
    List<FcmToken> findByUsername(String username);

    // Check if token already exists for this user
    Optional<FcmToken> findByUsernameAndToken(String username, String token);

    // Delete all tokens for a user (on logout)
    void deleteByUsername(String username);

    // Delete a specific token
    void deleteByToken(String token);
}