package com.example.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A revocable, read-only public link to the LIVE status of RFIs.
 * (The RFI twin of {@link ShareLink}, which covers projects / change orders.)
 *
 * Two kinds of link, stored in {@code scope}:
 *   PROJECT - every EXTERNAL RFI of one project (new RFIs appear automatically)
 *   RFI     - exactly one EXTERNAL RFI
 *
 * The {@code token} is a 192-bit random, URL-safe string and is the ONLY thing
 * that grants access to the public endpoint - never derive it from ids/names.
 *
 * Why a separate table instead of adding values to ShareScope: ShareLink.scope is
 * an enum column, and with MySQL + ddl-auto=update an existing enum column is
 * NOT altered, so inserting a new enum value would fail. A separate table (and a
 * plain VARCHAR scope here) cannot affect the working project share links.
 *
 * The table is created automatically on startup (ddl-auto=update).
 */
@Entity
@Table(name = "rfi_share_links", indexes = {
        @Index(name = "idx_rfi_share_project", columnList = "project_id"),
        @Index(name = "idx_rfi_share_rfi", columnList = "rfi_id")
})
public class RfiShareLink {

    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String SCOPE_RFI = "RFI";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    /** "PROJECT" or "RFI" (plain text on purpose - see class comment). */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    /** Display only (what the owner saw when creating the link). */
    @Column(name = "client", length = 255)
    private String client;

    /** Display only. The live name is always re-read from the project / RFI. */
    @Column(name = "project_name", length = 255)
    private String projectName;

    /** PROJECT scope: the project's id (not its name) so a rename keeps the link working. */
    @Column(name = "project_id")
    private Long projectId;

    /** RFI scope: the single RFI shared. */
    @Column(name = "rfi_id")
    private Long rfiId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** null = never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_viewed_at")
    private Instant lastViewedAt;

    public RfiShareLink() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getRfiId() { return rfiId; }
    public void setRfiId(Long rfiId) { this.rfiId = rfiId; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastViewedAt() { return lastViewedAt; }
    public void setLastViewedAt(Instant lastViewedAt) { this.lastViewedAt = lastViewedAt; }
}
