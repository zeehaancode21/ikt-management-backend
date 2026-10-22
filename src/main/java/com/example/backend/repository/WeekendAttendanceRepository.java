package com.example.backend.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.backend.entity.WeekendAttendance;

public interface WeekendAttendanceRepository extends JpaRepository<WeekendAttendance, Long> {

    Optional<WeekendAttendance> findByEmployeeNameAndDate(String employeeName, LocalDate date);

    List<WeekendAttendance> findByEmployeeNameOrderByDateDesc(String employeeName);

    List<WeekendAttendance> findByEmployeeNameAndDateBetweenOrderByDateDesc(
            String employeeName, LocalDate start, LocalDate end);

    List<WeekendAttendance> findByDateBetweenOrderByDateDesc(LocalDate start, LocalDate end);

    List<WeekendAttendance> findAllByOrderByDateDesc();
}