package com.example.backend.repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.example.backend.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    // Used to resolve a CC'd RFI address back to an internal employee
    // account, so that employee can get an in-app/push notification for
    // RFI updates (see RfiCcNotificationService) in addition to the email.
    Optional<User> findByEmailIgnoreCase(String email);

        // Tolerates duplicate emails (findBy... would throw if two users share an email)
    Optional<User> findFirstByEmailIgnoreCase(String email);

    // ------------------------------------------------------------------
    // RFI module access (one boolean column on users - see User.rfiAccess)
    // ------------------------------------------------------------------

    /** Ids of every user who has been granted RFI module access. */
    @Query("SELECT u.id FROM User u WHERE u.rfiAccess = true")
    List<Long> findRfiAccessUserIds();

    /** The flag for one login. Empty (no such user / NULL column) is treated as "no access". */
    @Query("SELECT u.rfiAccess FROM User u WHERE u.username = :username")
    Optional<Boolean> findRfiAccessByUsername(@Param("username") String username);

    /** Returns rows changed (0 = no such user). Writes only this one column. */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.rfiAccess = :allowed WHERE u.id = :id")
    int setRfiAccess(@Param("id") Long id, @Param("allowed") boolean allowed);

    // ------------------------------------------------------------------
    // Combined module access (RFI + Projects) - see ModuleAccessController
    // Both flags are columns on the users row; one query answers both.
    // ------------------------------------------------------------------

    /** Both flags of one login in a single query (nulls = no access). */
    interface ModuleAccessRow {
        Boolean getRfi();
        Boolean getProjects();
    }

    @Query("SELECT u.rfiAccess AS rfi, u.projectsAccess AS projects FROM User u WHERE u.username = :username")
    Optional<ModuleAccessRow> findModuleAccessByUsername(@Param("username") String username);

    /** Only users with at least one switch on (a short list, not every employee). */
    interface ModuleGrantRow {
        Long getId();
        Boolean getRfi();
        Boolean getProjects();
    }

    @Query("SELECT u.id AS id, u.rfiAccess AS rfi, u.projectsAccess AS projects FROM User u "
            + "WHERE u.rfiAccess = true OR u.projectsAccess = true")
    List<ModuleGrantRow> findModuleGrants();

    /** Returns rows changed (0 = no such user). Writes only this one column. */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.projectsAccess = :allowed WHERE u.id = :id")
    int setProjectsAccess(@Param("id") Long id, @Param("allowed") boolean allowed);

    // @Query("SELECT u.username FROM User u WHERE u.role = :role")
    // List<String> findAllEmployeeNames(@Param("role") String role);

    /** Every username, without loading full User rows. */
    @Query("SELECT u.username FROM User u")
    List<String> findAllUsernames();

    /** Only the columns the employee list needs (no entity loading). */
    interface EmployeeRow {
        Long getId();
        String getUsername();
        String getEmail();
        String getRole();
    }

    @Query("SELECT u.id AS id, u.username AS username, u.email AS email, u.role AS role FROM User u")
    List<EmployeeRow> findAllEmployeeRows();

    @Query("SELECT u.username FROM User u WHERE u.role IN :roles")
    List<String> findUsernamesByRoles(@Param("roles") List<String> roles);

    // ------------------------------------------------------------------
    // Notepad — stored as two extra columns on the users row
    // (note_content, note_updated_at). They are deliberately NOT mapped on
    // the User entity, so ordinary user lookups (login, JWT filter, employee
    // lists) never read the note text and it can never leak into a User JSON.
    // ------------------------------------------------------------------

    /** Read-only view of the note columns. */
    interface NoteRow {
        String getContent();
        Timestamp getUpdatedAt();
    }

    @Query(value = "SELECT note_content AS content, note_updated_at AS updatedAt "
            + "FROM users WHERE username = :username LIMIT 1", nativeQuery = true)
    Optional<NoteRow> findNoteByUsername(@Param("username") String username);

    /** Returns rows changed (0 = no such user). */
    @Transactional
    @Modifying
    @Query(value = "UPDATE users SET note_content = :content, note_updated_at = :updatedAt "
            + "WHERE username = :username", nativeQuery = true)
    int saveNote(@Param("username") String username,
                 @Param("content") String content,
                 @Param("updatedAt") Timestamp updatedAt);

    /** Empty pad -> NULL both columns, so the note takes zero space. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE users SET note_content = NULL, note_updated_at = NULL "
            + "WHERE username = :username", nativeQuery = true)
    int clearNote(@Param("username") String username);

    // ------------------------------------------------------------------
    // Task list — one more nullable TEXT column on the same users row
    // (task_content = compact JSON array). Same approach as the notepad:
    // not mapped on the User entity, no extra table, NULL when empty.
    // Needs: ALTER TABLE users ADD COLUMN task_content TEXT NULL;
    // ------------------------------------------------------------------

    @Query(value = "SELECT task_content FROM users WHERE username = :username LIMIT 1", nativeQuery = true)
    Optional<String> findTasksByUsername(@Param("username") String username);

    /** content = null clears the list. Returns rows changed (0 = no such user). */
    @Transactional
    @Modifying
    @Query(value = "UPDATE users SET task_content = :content WHERE username = :username", nativeQuery = true)
    int saveTasks(@Param("username") String username, @Param("content") String content);
}