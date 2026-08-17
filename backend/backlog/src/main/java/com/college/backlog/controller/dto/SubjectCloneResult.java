package com.college.backlog.controller.dto;

import java.util.List;

// Outcome of an apply: per-row CREATED / SKIPPED_EXISTS / ERROR plus tallies, mirroring the
// progression BatchResult shape.
public class SubjectCloneResult {
    private int created;
    private int skipped;
    private int errors;
    private List<ResultRow> rows;

    public SubjectCloneResult(int created, int skipped, int errors, List<ResultRow> rows) {
        this.created = created;
        this.skipped = skipped;
        this.errors = errors;
        this.rows = rows;
    }

    public int getCreated() { return created; }
    public int getSkipped() { return skipped; }
    public int getErrors() { return errors; }
    public List<ResultRow> getRows() { return rows; }

    public static class ResultRow {
        private String courseCode;
        private int semester;
        private String status;   // CREATED | SKIPPED_EXISTS | ERROR
        private String message;

        public ResultRow(String courseCode, int semester, String status, String message) {
            this.courseCode = courseCode;
            this.semester = semester;
            this.status = status;
            this.message = message;
        }

        public String getCourseCode() { return courseCode; }
        public int getSemester() { return semester; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
    }
}
