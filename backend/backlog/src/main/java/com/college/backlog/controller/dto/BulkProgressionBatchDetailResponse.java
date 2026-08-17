package com.college.backlog.controller.dto;

import java.util.List;

/**
 * One past run. The promoted students are a count on the header, not a list — a whole-college run
 * is ~20k rows and nobody reads those. The not-promoted rows come back in full: they are the small
 * set that records a decision (held back) or flags data needing a hand fix.
 */
public class BulkProgressionBatchDetailResponse {

    private BulkProgressionResultResponse batch;
    private List<Row> notPromoted;

    public BulkProgressionBatchDetailResponse(BulkProgressionResultResponse batch, List<Row> notPromoted) {
        this.batch = batch;
        this.notPromoted = notPromoted;
    }

    public BulkProgressionResultResponse getBatch() { return batch; }
    public List<Row> getNotPromoted() { return notPromoted; }

    public static class Row {
        private String rollNo;
        private int semesterFrom;
        private String outcome;

        public Row(String rollNo, int semesterFrom, String outcome) {
            this.rollNo = rollNo;
            this.semesterFrom = semesterFrom;
            this.outcome = outcome;
        }

        public String getRollNo() { return rollNo; }
        public int getSemesterFrom() { return semesterFrom; }
        public String getOutcome() { return outcome; }
    }
}
