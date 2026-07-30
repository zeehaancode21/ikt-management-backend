package com.example.backend.repository;

import com.example.backend.entity.FavoriteClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface FavoriteClientRepository extends JpaRepository<FavoriteClient, Long> {

    Optional<FavoriteClient> findByClientNameIgnoreCase(String clientName);

    boolean existsByClientNameIgnoreCase(String clientName);

    @Transactional
    void deleteByClientNameIgnoreCase(String clientName);
}