package com.college.backlog.controller.dto;

import java.util.List;

// Outcome of a clone apply: per-row CREATED / SKIPPED_EXISTS / ERROR plus tallies, mirroring the
// progression BatchResult shape. Rows are SubjectRowResult, shared with the CSV importer.
public class SubjectCloneResult {
    private int created;
    private int skipped;
    private int errors;
    private List<SubjectRowResult> rows;

    /**
     * Null normally. Set when the operation SUCCEEDED but something after it did not — today only
     * a failed audit write. The rows are committed and correct either way; this is the disclosure
     * that the record of them is incomplete, so the admin can escalate instead of the gap being
     * silent. Not an error: the result above is the truth about what happened.
     */
    private String warning;

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

    public String getWarning() { return warning; }
    public void setWarning(String warning) { this.warning = warning; }
}
