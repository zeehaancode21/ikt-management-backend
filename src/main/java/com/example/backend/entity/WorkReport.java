package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "work_report")
public class WorkReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String employeeName;

    private LocalDate date;

    private String client;

    private String project;

    @Enumerated(EnumType.STRING)
    private WorkType workType;

    private Double time;

    @Column(columnDefinition = "TEXT")
    private String description;

   @CreationTimestamp
   @Column(updatable = false)
   private LocalDateTime createdAt;

    public enum WorkType {
        E_PLAN,
        SHOP_DRAWING,
        LINKING,
        PART_DRAWING,
        DISCUSSION_STUDY,
        CHECKING,
        MODELING,
        TRAINING,
        PRACTICING,
        MISCELLANEOUS,
        ESTIMATION,
        DESIGNING
    }
}