package com.college.backlog.service;

import java.time.Year;

/**
 * The academic-year range rule, in one place. Years are stored as a single START year (2025 = AY
 * 2025-26, see docs/adr/backlog-progression.md); a usable one runs from {@code MIN_YEAR} through
 * next year, so the coming year's catalog can be prepared in advance but an arbitrary future one
 * cannot be stamped onto a subject.
 *
 * Why a holder rather than annotations: the upper bound is relative to now, so it CANNOT be a Bean
 * Validation {@code @Max} — a constant would drift stale every January. That is how the rule ended
 * up restated in ProgressionService and SubjectCloneController and missing entirely on
 * subject-create, where {@code @NotNull} on a primitive {@code int} silently validated nothing.
 * A DTO annotation can only be a coarse floor; this is the authoritative check.
 *
 * Sibling of {@link CourseCodes}, which owns the related prefix==year invariant.
 */
public final class AcademicYears {

    /** Earliest year the portal keeps records for. */
    public static final int MIN_YEAR = 2000;

    private AcademicYears() {}

    /** Latest settable year: next year, so an upcoming catalog can be set up ahead of time. */
    public static int maxYear() {
        return Year.now().getValue() + 1;
    }

    public static boolean isInRange(int year) {
        return year >= MIN_YEAR && year <= maxYear();
    }

    /**
     * @throws IllegalArgumentException out of range — matching ProgressionService's existing
     *     contract, so its callers are unaffected. Callers that answer HTTP wrap it as a 400,
     *     since IllegalArgumentException has no GlobalExceptionHandler mapping and would otherwise
     *     surface as a 500.
     */
    public static void assertInRange(int year) {
        if (!isInRange(year)) {
            throw new IllegalArgumentException("Academic year " + year + " is out of range.");
        }
    }
}
