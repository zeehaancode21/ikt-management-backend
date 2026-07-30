package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * FavoriteClient.java
 * ---------------------------------------------------------------------
 * Backs the "favorite clients" chip strip + manage popup on the Hours
 * dashboard. Deliberately independent of the /clients roster table
 * (ClientController) and of ProjectStatus — it just remembers which
 * client *names* (as they appear in ProjectStatus) the user has pinned.
 * ---------------------------------------------------------------------
 */
@Entity
@Table(
        name = "favorite_clients",
        uniqueConstraints = @UniqueConstraint(columnNames = "client_name")
)
public class FavoriteClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_name", nullable = false, unique = true, length = 255)
    private String clientName;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public FavoriteClient() {
    }

    public FavoriteClient(String clientName) {
        this.clientName = clientName;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}