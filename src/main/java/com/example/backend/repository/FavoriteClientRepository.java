package com.example.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.FavoriteClient;


public interface FavoriteClientRepository extends JpaRepository<FavoriteClient, Long> {

    boolean existsByClientNameIgnoreCase(String clientName);

    @Transactional
    void deleteByClientNameIgnoreCase(String clientName);
}