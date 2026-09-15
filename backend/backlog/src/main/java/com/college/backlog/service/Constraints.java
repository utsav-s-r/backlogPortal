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
 * Sibling of {@link Semesters} / {@link AcademicYears}. Matching on the name
 * is string-ish, but the names are fixed by the Flyway migrations and it is strictly better than
 * asserting the cause outright: an unrecognised violation now falls through to the generic
 * "conflicts with existing data" 409 in GlobalExceptionHandler, which also logs it.
 */
public final class Constraints {

    /** subjects (course_code, academic_year_offered) — one course code per academic year. */
    public static final String SUBJECT_CODE_YEAR = "uq_subjects_code_year";

    /** registrations (roll_no, exam_cycle_id) WHERE status='SUBMITTED' — one pending per cycle. */
    public static final String PENDING_REGISTRATION_PER_CYCLE = "uq_pending_reg_per_cycle";

    /** students (roll_no) — the USN primary key. */
    public static final String STUDENT_ROLL_NO = "students_pkey";

    /** proctor_students (roll_no) — one proctor per student. */
    public static final String PROCTOR_ASSIGNMENT_ROLL_NO = "proctor_students_pkey";

    private Constraints() {}

    /**
     * Whether this violation was raised by the named constraint. Postgres puts the name in the
     * message of the most specific cause, wrapped differently by the driver, Hibernate and Spring,
     * so the match is case-insensitive and position-free — but on the WHOLE name: students_pkey is a
     * substring of proctor_students_pkey, and a plain contains() reports one as the other.
     */
    public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        String name = constraintName.toLowerCase();
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && containsWholeName(message.toLowerCase(), name)) {
                return true;
            }
            if (t.getCause() == t) break; // self-referential cause: stop rather than spin
        }
        return false;
    }

    /** {@code name} occurring with no identifier character ([a-z0-9_]) directly on either side. */
    private static boolean containsWholeName(String text, String name) {
        for (int i = text.indexOf(name); i >= 0; i = text.indexOf(name, i + 1)) {
            int end = i + name.length();
            if ((i == 0 || !isIdentifierChar(text.charAt(i - 1)))
                    && (end == text.length() || !isIdentifierChar(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }
}
