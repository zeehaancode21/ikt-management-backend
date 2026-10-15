package com.example.backend.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single source of truth for "is this a working day?" on the backend.
 *
 * Non-working days are:
 *   • every Saturday and Sunday
 *   • the government / company holidays listed in GOVT_HOLIDAYS below
 *
 * The list mirrors the "IKT HOLIDAY LIST" shown in HolidayHover.tsx.
 * >>> Add next year's holidays here (yyyy, month, day) when the new list is published. <<<
 *
 * The frontend loads the same list from GET /leaves/holidays, so you only
 * ever have to update it in this one place for leave validation.
 */
public final class HolidayCalendar {

    public record Holiday(LocalDate date, String name) {}

    private static final Map<LocalDate, String> GOVT_HOLIDAYS = new TreeMap<>();

    static {
        // ── 2026 ──
        add(2026, 1, 1, "New Year's Day");
        add(2026, 1, 15, "Makara Sankranti");
        add(2026, 1, 26, "Republic Day");
        add(2026, 3, 19, "Ugadi");
        add(2026, 5, 1, "May Day");
        add(2026, 9, 14, "Ganesh Chaturthi");
        add(2026, 10, 2, "Gandhi Jayanthi");
        add(2026, 10, 20, "Ayudha Puja");
        add(2026, 10, 21, "Vijayadashami");
        add(2026, 11, 10, "Deepavali");
    }

    private static void add(int y, int m, int d, String name) {
        GOVT_HOLIDAYS.put(LocalDate.of(y, m, d), name);
    }

    private static final DateTimeFormatter PRETTY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private HolidayCalendar() {
    }

    public static boolean isWeekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    /** Name of the government holiday on that date, or null if it isn't one. */
    public static String holidayName(LocalDate d) {
        return GOVT_HOLIDAYS.get(d);
    }

    public static boolean isNonWorkingDay(LocalDate d) {
        return isWeekend(d) || GOVT_HOLIDAYS.containsKey(d);
    }

    /**
     * @return a user-facing error message if leave cannot be taken on this
     *         date, or null when the date is a normal working day.
     */
    public static String blockReason(LocalDate d) {
        if (d == null) return null;
        if (isWeekend(d)) {
            String day = d.getDayOfWeek() == DayOfWeek.SATURDAY ? "Saturday" : "Sunday";
            return d.format(PRETTY) + " is a " + day + ", which is already a holiday. "
                    + "Leave cannot be applied on Saturdays or Sundays.";
        }
        String name = GOVT_HOLIDAYS.get(d);
        if (name != null) {
            return d.format(PRETTY) + " is already a holiday (" + name + "). "
                    + "Leave cannot be applied on a holiday.";
        }
        return null;
    }

    /** Number of working days (Mon–Fri, excluding govt holidays) in [from, to] inclusive. */
    public static int workingDaysBetween(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!isNonWorkingDay(d)) count++;
        }
        return count;
    }

    public static List<Holiday> all() {
        List<Holiday> list = new ArrayList<>();
        GOVT_HOLIDAYS.forEach((d, n) -> list.add(new Holiday(d, n)));
        return list;
    }
}
