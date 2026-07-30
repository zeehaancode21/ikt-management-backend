package com.example.backend.entity;

public enum DateType {
    SINGLE,
    RANGE,
    HALF_DAY,
    // "Short Leave" — an hours-based leave for when an employee only needs
    // to step away for part of a day (e.g. a doctor's appointment).
    // Governed by two caps enforced server-side (see LeaveController):
    //   • Max 2 hours per calendar day (requests that would push a single
    //     day's total above 2 hours are automatically converted to HALF_DAY)
    //   • Max 4 hours per calendar month
    SHORT_LEAVE
}