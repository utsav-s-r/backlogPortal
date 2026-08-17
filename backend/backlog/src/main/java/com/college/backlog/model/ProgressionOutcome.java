package com.college.backlog.model;

/**
 * What a bulk progression run did to one student. Stored as the enum name (varchar) with a DB CHECK
 * on the values — same convention as {@link EventAction}.
 */
public enum ProgressionOutcome {
    /** currentSemester advanced by 2. The only outcome that writes semesterTo. */
    PROMOTED,
    /** On the admin's exclusion list — typically detained, held back deliberately. */
    EXCLUDED_BY_ADMIN,
    /** Already at semester 8; there is nowhere to advance to. */
    SKIPPED_AT_MAX,
    /** Odd currentSemester or even entrySemester — legacy data that needs a hand fix, never guessed. */
    SKIPPED_INVALID_SEMESTER
}
