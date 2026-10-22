package com.example.backend.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private String originalFileName;
    private String fileType;
    private Long fileSize;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Lob
    @Column(columnDefinition = "LONGBLOB")
    private byte[] fileData;

    private String description;

    @Column(name = "category")
    private String category;

    @Column(name = "project_name")
    private String projectName;

    private String uploadedBy;

    @Column(name = "upload_status")
    private String uploadStatus = "PROCESSING";

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private Folder folder;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
}