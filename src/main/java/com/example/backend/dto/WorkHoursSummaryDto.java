package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Response payload for GET /reports/summary.
 *
 * Hours are grouped into three top-level buckets, as requested:
 *   1) Modeling
 *   2) Checking
 *   3) "Drawing" group = E Plan + Shop Drawing + Linking + Part Drawing
 *
 * totalHours = modelingHours + checkingHours + drawingGroupHours
 *
 * The individual component/class breakdown (E Plan, Shop Drawing, Linking,
 * Part Drawing, Modeling, Checking) is still exposed via hoursByType so the
 * UI can show each one separately as well as the combined totals.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkHoursSummaryDto {

    /** Echoes back the filters that were applied (null = "all"). */
    private String client;
    private String project;

    /** Individual hours per WorkType enum name, e.g. "E_PLAN" -> 12.5 */
    private Map<String, Double> hoursByType;

    private double modelingHours;
    private double checkingHours;

    /** E_PLAN + SHOP_DRAWING + LINKING + PART_DRAWING */
    private double drawingGroupHours;

    /** modelingHours + checkingHours + drawingGroupHours */
    private double totalHours;
}