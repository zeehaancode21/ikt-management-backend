package com.example.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A revocable, read-only public link to live project data.
 *
 * The {@code token} is a 192-bit random, URL-safe string. It is the ONLY thing
 * that grants access to the public endpoint, so it must stay unguessable:
 * never derive it from ids, names, or timestamps.
 *
 * The table is created automatically on startup
 * (spring.jpa.hibernate.ddl-auto=update). Explicit getters/setters are used
 * (no Lombok) on purpose.
 */
@Entity
@Table(name = "share_links")
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private ShareScope scope;

    /**
     * CLIENT scope: the client name the link covers.
     * PROJECT scope: the project's client at creation time (display only).
     */
    @Column(name = "client", length = 255)
    private String client;

    /** CLIENT scope only. null = every year, otherwise just that year. */
    @Column(name = "share_year", length = 16)
    private String shareYear;

    /**
     * PROJECT scope only. Stored as the project's id (not its name) so the link
     * keeps working if the project is renamed later.
     */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * When false (default) the public view omits internal-only fields:
     * project team names, project remarks and change-order remarks.
     */
    @Column(name = "show_details", nullable = false)
    private boolean showDetails = false;

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

    public ShareLink() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public ShareScope getScope() { return scope; }
    public void setScope(ShareScope scope) { this.scope = scope; }

    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }

    public String getShareYear() { return shareYear; }
    public void setShareYear(String shareYear) { this.shareYear = shareYear; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public boolean isShowDetails() { return showDetails; }
    public void setShowDetails(boolean showDetails) { this.showDetails = showDetails; }

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