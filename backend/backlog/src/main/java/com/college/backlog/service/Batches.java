package com.college.backlog.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rows one bulk request may carry. Sibling of {@link Semesters} / {@link AcademicYears}: one
 * definition for all four bulk endpoints, so the number cannot drift between them.
 *
 * <p>Bounds WALL CLOCK, not memory — 500 rows of JSON is nothing, but each row is its own
 * transaction against remote Neon, so cost is round trips × rows. Sized for the heaviest endpoint,
 * student import: {@code createStudent} is REQUIRES_NEW and its {@code seedLinearTimeline} writes
 * one {@code student_semester_terms} row per semester from entry to 8.
 *
 * <p>An uncapped batch can outlive the proxy or the browser, and since no loop is transactional the
 * rows already written stay written while the caller sees only a failure and no result table — the
 * "committed but unreported" hole the per-row {@code catch (RuntimeException)} clauses close,
 * reached via the network instead.
 */
public final class Batches {

    private Batches() {}

    /** Rows per bulk request, shared by subject import, student import, clone apply, proctor claim. */
    public static final int MAX_ROWS = 500;

    /**
     * Refuse an oversized batch BEFORE any row is processed, so nothing is written. NOT a
     * truncation: importing the first {@code MAX_ROWS} and dropping the rest reports success for
     * work that did not happen, which is worse than no cap.
     *
     * <p>Throws {@link ResponseStatusException}, not {@code IllegalArgumentException}: unlike the
     * sibling validators, whose callers wrap it, a bare IAE escaping a controller becomes a 500 —
     * {@code GlobalExceptionHandler} deliberately does not map IAE centrally, since
     * NumberFormatException extends it.
     *
     * <p>400 not 413: 413 is transport-level byte size; this is a row count the caller can fix and
     * resubmit, so the message names what they sent. All four loops skip existing rows, so
     * re-sending an overlapping batch is safe — hence the second sentence.
     *
     * @param noun what the rows are, for the message ("rows", "students")
     */
    public static void assertWithinLimit(int size, String noun) {
        if (size > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "At most " + MAX_ROWS + " " + noun + " per batch (you sent " + size + "). "
                    + "Send them in smaller batches — anything already saved is skipped.");
        }
    }
}
