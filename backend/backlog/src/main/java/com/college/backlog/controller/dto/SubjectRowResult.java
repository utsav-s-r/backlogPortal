package com.college.backlog.controller.dto;

/**
 * Per-row outcome of a bulk subject operation (clone apply, CSV import) — the subject-side
 * counterpart of {@link ProgressionRowResult}, keyed by course code instead of USN.
 *
 * <p>status: CREATED | SKIPPED_EXISTS | ERROR | WOULD_CREATE | WOULD_SKIP.
 *
 * <p>Shared by both callers on purpose: as two copies the status vocabulary drifts, and the admin
 * then reads the same word meaning different things on two tabs.
 */
public class SubjectRowResult {
    private String courseCode;
    // Boxed, like ProgressionRowResult.semester: a CSV row with a blank semester cell has no
    // semester to report, and a primitive would flatten that to a literal "Sem 0" in the results
    // table. The frontend already renders null as "—".
    private Integer semester;
    private String status;
    private String message;

    public SubjectRowResult(String courseCode, Integer semester, String status, String message) {
        this.courseCode = courseCode;
        this.semester = semester;
        this.status = status;
        this.message = message;
    }

    public String getCourseCode() { return courseCode; }
    public Integer getSemester() { return semester; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
}
