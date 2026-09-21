package com.college.backlog.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The college's clock. Timestamps are stored as instants (V6); anything PRINTED has to be rendered
 * in a named zone, and it must not be the server's.
 *
 * <p>{@code ZoneId.systemDefault()} is the bug in disguise: Render runs UTC, so a form printed for
 * a registration made between 00:00 and 05:30 IST showed the PREVIOUS day — and it worked
 * perfectly on a developer's machine, which is already in IST. Naming the zone is what makes the
 * output the same everywhere.
 *
 * <p>Only the SERVER-rendered output (the PDFs) goes through here. The API sends instants and the
 * browser renders them in the viewer's own zone, which is what a viewer expects.
 *
 * <p>Sibling of {@link ExamMonths} / {@link AcademicYears} / {@link Semesters}.
 */
public final class Times {

    /** Where the college is. A constant, not configuration: it is a fact about the institution,
     *  and an env var for it is one typo away from silently re-dating every form. */
    public static final ZoneId COLLEGE = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Times() {}

    /** The calendar date of {@code instant} AT THE COLLEGE, as dd/MM/yyyy. "" for null. */
    public static String formatDate(Instant instant) {
        return instant == null ? "" : DATE.format(instant.atZone(COLLEGE));
    }
}
