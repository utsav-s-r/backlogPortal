package com.college.backlog.service;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB constraint names, and the one question worth asking about a {@link DataIntegrityViolationException}:
 * <em>which</em> constraint rejected this row?
 *
 * Three call sites used to answer that by assumption — each caught the exception and reported one
 * specific, friendly message ("Already exists", "You already have a pending registration") for ANY
 * violation. The clone path was the dangerous one: its rows are passed to {@code createSubject}
 * programmatically, so {@code @Valid} never runs on them, and a row rejected for some other reason
 * was reported as already-existing — leaving the admin believing the target year's catalog was
 * complete.
 *
 * Sibling of {@link Semesters} / {@link AcademicYears} / {@link CourseCodes}. Matching on the name
 * is string-ish, but the names are fixed by the Flyway migrations and it is strictly better than
 * asserting the cause outright: an unrecognised violation now falls through to the generic
 * "conflicts with existing data" 409 in GlobalExceptionHandler, which also logs it.
 */
public final class Constraints {

    /** subjects (course_code, academic_year_offered) — one course code per academic year. */
    public static final String SUBJECT_CODE_YEAR = "uq_subjects_code_year";

    /** registrations (roll_no, exam_cycle_id) WHERE status='SUBMITTED' — one pending per cycle. */
    public static final String PENDING_REGISTRATION_PER_CYCLE = "uq_pending_reg_per_cycle";

    private Constraints() {}

    /**
     * Whether this violation was raised by the named constraint. Postgres puts the name in the
     * message of the most specific cause; the check is case-insensitive and substring-based because
     * the surrounding text differs between the driver, Hibernate and Spring's translation layer.
     */
    public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase().contains(constraintName.toLowerCase())) {
                return true;
            }
            if (t.getCause() == t) break; // self-referential cause: stop rather than spin
        }
        return false;
    }
}
