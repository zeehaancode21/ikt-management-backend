package com.example.backend.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "folders")
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * null  → root-level folder
     * non-null → child folder nested inside another folder
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private Folder parent;

    /** Convenience getter so the frontend gets a plain parentId field (not a full object). */
    @Column(name = "parent_id", insertable = false, updatable = false)
    private Long parentId;

   /** Child sub-folders (never circular — tree is strictly parent → child). */
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @BatchSize(size = 25)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnoreProperties({"parent", "hibernateLazyInitializer", "handler"})
    private List<Folder> subFolders = new ArrayList<>();

    /** Files uploaded directly into this folder. */
    @OneToMany(mappedBy = "folder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @BatchSize(size = 25)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private List<Document> documents = new ArrayList<>();

    // ── Derived state helpers (used by controller, not persisted) ─────────────

    /**
     * A folder is a "leaf" when it has at least one uploaded document.
     * Leaf folders cannot receive sub-folders.
     */
    public boolean isLeaf() {
        return documents != null && !documents.isEmpty();
    }

    /**
     * A folder is a "branch" when it already contains sub-folders.
     * Branch folders cannot receive direct file uploads.
     */
    public boolean isBranch() {
        return subFolders != null && !subFolders.isEmpty();
    }
}