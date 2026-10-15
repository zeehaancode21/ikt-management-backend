// AttachmentData.java
package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Holds the actual bytes of an uploaded attachment, inside the database.
 *
 * Why: Render's free plan wipes the server's local disk on every redeploy,
 * restart and spin-down, so files saved to disk vanish while the database
 * still points at them. The database survives all of that.
 *
 * It lives in its OWN table (not inside Attachment) on purpose: Attachment is
 * sent to the frontend and loaded with every chat message, and we don't want
 * the file bytes dragged along each time.
 *
 * ON DELETE CASCADE: when an Attachment row is deleted, its bytes are removed
 * by the database automatically, so nothing is left behind.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "attachment_data")
public class AttachmentData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attachment_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Attachment attachment;

    @Lob
    @Column(columnDefinition = "LONGBLOB", nullable = false)
    private byte[] data;

    public AttachmentData(Attachment attachment, byte[] data) {
        this.attachment = attachment;
        this.data = data;
    }
}