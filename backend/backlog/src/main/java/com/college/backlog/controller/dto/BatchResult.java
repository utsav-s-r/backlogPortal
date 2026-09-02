package com.college.backlog.controller.dto;

import java.util.List;

// Summary of a bulk op (student import, proctor claim, subject import). With dryRun true nothing
// was written and the counts describe what would happen.
//
// Generic in the row type rather than one class per caller: the envelope is identical everywhere
// (same four counts, same JSON field name `results`), while the row genuinely differs — it is keyed
// by USN for students and by course code for subjects, and the frontend reads that field by name.
public class BatchResult<R> {
    private boolean dryRun;
    private int created;
    private int skipped;
    private int errors;
    private List<R> results;

    public BatchResult(boolean dryRun, int created, int skipped, int errors, List<R> results) {
        this.dryRun = dryRun;
        this.created = created;
        this.skipped = skipped;
        this.errors = errors;
        this.results = results;
    }

    public boolean isDryRun() { return dryRun; }
    public int getCreated() { return created; }
    public int getSkipped() { return skipped; }
    public int getErrors() { return errors; }
    public List<R> getResults() { return results; }
}
