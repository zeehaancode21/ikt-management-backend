package com.example.backend.repository;

import com.example.backend.entity.WorkReport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface WorkReportRepository extends JpaRepository<WorkReport, Long> {

    /** Employee: all own reports, newest first */
    List<WorkReport> findByEmployeeNameOrderByDateDesc(String employeeName);

    /** Employee: own reports for a specific date */
    List<WorkReport> findByEmployeeNameAndDateOrderByIdAsc(String employeeName, LocalDate date);

    /** Owner: all reports, newest first */
    List<WorkReport> findAllByOrderByDateDesc();

    /**
     * Owner: filtered query — both params are optional.
     * Pass null to skip that filter.
     */
    @Query("""
           SELECT w FROM WorkReport w
           WHERE (:date         IS NULL OR w.date         = :date)
             AND (:employeeName IS NULL OR w.employeeName = :employeeName)
           ORDER BY w.date DESC, w.employeeName ASC
           """)
    List<WorkReport> findAllFiltered(
            @Param("date")         LocalDate date,
            @Param("employeeName") String    employeeName
    );

    @Modifying
    @Transactional
    void deleteByEmployeeName(String username);

    /**
     * Renames a project across all work reports that reference it.
     * Matches ignoring case and surrounding whitespace, since WorkReport.project
     * is free-standing denormalized text that can drift slightly from
     * Project.projectName (extra spaces, different casing at entry time) —
     * a strict "=" match would silently skip those rows instead of renaming them.
     */
    @Modifying
    @Transactional
    @Query("UPDATE WorkReport w SET w.project = :newName " +
           "WHERE TRIM(LOWER(w.project)) = TRIM(LOWER(:oldName))")
    int renameProject(
            @Param("oldName") String oldName,
            @Param("newName") String newName
    );

    /**
     * Dashboard: total hours (SUM of the `time` column) grouped by work type,
     * optionally filtered by client and/or project. Pass null to skip a filter.
     *
     * Each row of the result is: [0] = WorkReport.WorkType, [1] = Double (sum of hours).
     */
    @Query("""
           SELECT w.workType, COALESCE(SUM(w.time), 0.0)
           FROM WorkReport w
           WHERE (:client  IS NULL OR w.client  = :client)
             AND (:project IS NULL OR w.project = :project)
           GROUP BY w.workType
           """)
    List<Object[]> sumHoursByWorkType(
            @Param("client")  String client,
            @Param("project") String project
    );
}