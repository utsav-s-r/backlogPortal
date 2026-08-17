package com.college.backlog.controller.dto;

import java.util.List;

// Summary of a bulk op (student import, proctor claim). With dryRun true nothing was written and
// the counts describe what would happen.
public class BatchResult {
    private boolean dryRun;
    private int created;
    private int skipped;
    private int errors;
    private List<ProgressionRowResult> results;

    public BatchResult(boolean dryRun, int created, int skipped, int errors,
                       List<ProgressionRowResult> results) {
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
    public List<ProgressionRowResult> getResults() { return results; }
}
