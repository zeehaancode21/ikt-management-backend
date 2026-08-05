package com.example.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "weekend_attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_weekend_attendance_employee_date",
                columnNames = {"employee_name", "date"}
        )
)
public class WeekendAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_name", nullable = false)
    private String employeeName;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "day_of_week")
    private String dayOfWeek;

    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;

    @Column(name = "check_out_time")
    private LocalDateTime checkOutTime;

    @Column(name = "total_hours")
    private Double totalHours;

    private String client;

    private String project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WeekendAttendanceStatus status = WeekendAttendanceStatus.CHECKED_IN;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}