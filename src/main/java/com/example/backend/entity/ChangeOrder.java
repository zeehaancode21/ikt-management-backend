package com.example.backend.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;

@Entity
@Data
@AllArgsConstructor
@Table(name = "change_orders")
public class ChangeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Column(nullable = false)
    private String co;

    @Column(columnDefinition = "TEXT", name = "description")
    private String description;

    private String status;

    private Double amount;

    @Column(name = "ifa_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate ifaDate;

    @Column(name = "ifa_per")
    private String ifaPer;

    @Column(name = "iff_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate iffDate;

    @Column(name = "iff_per")
    private String iffPer;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    public ChangeOrder() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getCo() { return co; }
    public void setCo(String co) { this.co = co; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public LocalDate getIfaDate() { return ifaDate; }
    public void setIfaDate(LocalDate ifaDate) { this.ifaDate = ifaDate; }

    public String getIfaPer() { return ifaPer; }
    public void setIfaPer(String ifaPer) { this.ifaPer = ifaPer; }

    public LocalDate getIffDate() { return iffDate; }
    public void setIffDate(LocalDate iffDate) { this.iffDate = iffDate; }

    public String getIffPer() { return iffPer; }
    public void setIffPer(String iffPer) { this.iffPer = iffPer; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}