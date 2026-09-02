package com.college.backlog.controller.dto;

import java.util.List;

// Outcome of a clone apply: per-row CREATED / SKIPPED_EXISTS / ERROR plus tallies, mirroring the
// progression BatchResult shape. Rows are SubjectRowResult, shared with the CSV importer.
public class SubjectCloneResult {
    private int created;
    private int skipped;
    private int errors;
    private List<SubjectRowResult> rows;

    public SubjectCloneResult(int created, int skipped, int errors, List<SubjectRowResult> rows) {
        this.created = created;
        this.skipped = skipped;
        this.errors = errors;
        this.rows = rows;
    }

    public int getCreated() { return created; }
    public int getSkipped() { return skipped; }
    public int getErrors() { return errors; }
    public List<SubjectRowResult> getRows() { return rows; }
}
