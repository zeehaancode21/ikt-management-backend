package com.example.backend.dto;

import java.time.LocalDate;

import com.example.backend.entity.DateType;
import com.example.backend.entity.LeaveType;
import com.example.backend.entity.Status;

/**
 * Lightweight, read-only projection used by GET /leaves/today.
 *
 * It is filled straight from the existing LeaveRequest table (no new table)
 * and only carries the handful of columns the "On leave today" panel needs,
 * so the full LeaveRequest entity (reason, pending* fields, etc.) is never
 * loaded into memory and the leave reason is never exposed to other staff.
 */
public interface LeaveTodayRow {

    String getEmployeeName();

    LeaveType getLeaveType();

    DateType getDateType();

    String getHalfSession();

    Double getHours();

    Double getDays();

    LocalDate getFromDate();

    LocalDate getToDate();

    Status getStatus();

    Boolean getSurplusPermission();
}