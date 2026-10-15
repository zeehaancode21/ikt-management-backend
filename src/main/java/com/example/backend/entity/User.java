package com.example.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users", indexes = {
        // every request (JWT filter), login and role lookup finds a user by username
        @Index(name = "idx_users_username", columnList = "username")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String email;

    // WRITE_ONLY: Jackson can deserialize it from request bodies (login, register)
    // but will NEVER serialize it into any response — password never leaks over the wire.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(nullable = false)
    private String role = "USER";

    // RFI module access, switched on/off by the OWNER in Admin Console -> Access Control.
    // One tiny column on the existing users row (no new table). NULL / false = no access.
    // Owners never need it - they always have access.
    // @JsonIgnore: never read from or written to JSON (register returns a User; login binds one),
    // so it can only ever be changed through PUT /api/rfi-access/{id} by the OWNER.
    @JsonIgnore
    @Column(name = "rfi_access")
    private Boolean rfiAccess = false;

    // Projects (Dashboard) access, switched on/off by the OWNER in Admin Console -> Access Control.
    // Same idea as rfiAccess: one tiny boolean column on the existing users row, no new table.
    // NULL / false = no access. Owners always have access.
    // Run once if you use JPA_DDL_AUTO=none:  ALTER TABLE users ADD COLUMN projects_access BOOLEAN NULL;
    @JsonIgnore
    @Column(name = "projects_access")
    private Boolean projectsAccess = false;
}