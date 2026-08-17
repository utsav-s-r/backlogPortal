package com.college.backlog.service;

/**
 * The studiable-semester range, in one place. A programme runs 1..8 and that single range governs
 * eligibility, current/entry semester, subject offerings, cloning and progression — see
 * docs/adr/backlog-progression.md.
 *
 * Keep it single. {@code StudentManagementService.validateSemesters} bounds
 * {@code Student.currentSemester} by this range and {@code EligibilityService} returns an EMPTY
 * window above 8, so a wider range anywhere silently locks the student out of registering.
 * {@code ProgressionService.backfillLinear} seeds up to {@link #MAX} for the same reason.
 *
 * <p><b>Parity is NARROWER than the range, and applies to {@code Student} only.</b> A student sits
 * in an even semester (2..8) and joins at an odd one (1..7), because an academic year is a
 * semester PAIR and both entry and progression happen at a year boundary. That constrains
 * {@code Student.currentSemester}/{@code entrySemester} and nothing else: {@link #isStudiable}
 * stays 1..8 for {@code Subject.semester}, {@code StudentSemesterTerm.semester}, cloning and the
 * eligibility window — a sem-2 student's backlogs are in sem 1, so odd semesters must stay
 * registrable. Applying parity to those would lock every student out of half their backlogs.
 *
 * Sibling of {@link AcademicYears} and {@link CourseCodes}. Enforced in application code only —
 * {@code student_semester_terms.semester} is a plain integer with no CHECK, by decision.
 */
public final class Semesters {

    public static final int MIN = 1;
    public static final int MAX = 8;

    private Semesters() {}

    public static boolean isStudiable(int semester) {
        return semester >= MIN && semester <= MAX;
    }

    /**
     * @throws IllegalArgumentException out of range — matching ProgressionService's existing
     *     contract, so its catch sites are unaffected. HTTP callers wrap it as a 400.
     */
    public static void assertStudiable(int semester) {
        if (!isStudiable(semester)) {
            throw new IllegalArgumentException(
                "Semester must be between " + MIN + " and " + MAX + ".");
        }
    }

    /** A student's current semester: even, 2..8. Progression moves it +2 (see BulkProgressionService). */
    public static boolean isCurrentSemester(int semester) {
        return isStudiable(semester) && semester % 2 == 0;
    }

    /** A student's entry semester: odd, 1..7 — entry is always at the start of an academic year. */
    public static boolean isEntrySemester(int semester) {
        return isStudiable(semester) && semester % 2 == 1;
    }

    /** @throws IllegalArgumentException not an even 2..8 — wrapped as a 400 by HTTP callers. */
    public static void assertCurrentSemester(int semester) {
        if (!isCurrentSemester(semester)) {
            throw new IllegalArgumentException(
                "Current semester must be an even semester (2, 4, 6 or 8).");
        }
    }

    /** @throws IllegalArgumentException not an odd 1..7 — wrapped as a 400 by HTTP callers. */
    public static void assertEntrySemester(int semester) {
        if (!isEntrySemester(semester)) {
            throw new IllegalArgumentException(
                "Entry semester must be an odd semester (1, 3, 5 or 7).");
        }
    }
}
