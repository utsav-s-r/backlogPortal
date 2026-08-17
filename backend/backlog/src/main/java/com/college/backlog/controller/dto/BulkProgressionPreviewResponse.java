package com.college.backlog.controller.dto;

import java.util.List;

/**
 * What a bulk run would do. Promoted and at-the-cap are COUNTS only — a whole-college run is ~20k
 * students and the screen shows numbers, not names. Only the rows a human must actually CHECK come
 * back in full: the admin's own exclusions and legacy rows with an invalid semester. Both are small
 * by nature, unlike the semester-8 students, who are not.
 */
public class BulkProgressionPreviewResponse {

    /** Feeds straight back as the commit's expectedCount. */
    private long promoteCount;
    /** Students already at semester 8. A COUNT, never a list — in a whole-college run they are
     *  roughly a quarter of the student body and nobody reads their names. */
    private long atMaxCount;
    private List<Row> notPromoted;

    public BulkProgressionPreviewResponse(long promoteCount, long atMaxCount, List<Row> notPromoted) {
        this.promoteCount = promoteCount;
        this.atMaxCount = atMaxCount;
        this.notPromoted = notPromoted;
    }

    public long getPromoteCount() { return promoteCount; }
    public long getAtMaxCount() { return atMaxCount; }
    public List<Row> getNotPromoted() { return notPromoted; }

    /** One student who will not move, and why. */
    public static class Row {
        private String rollNo;
        private int currentSemester;
        private int entrySemester;
        private String outcome;

        public Row(String rollNo, int currentSemester, int entrySemester, String outcome) {
            this.rollNo = rollNo;
            this.currentSemester = currentSemester;
            this.entrySemester = entrySemester;
            this.outcome = outcome;
        }

        public String getRollNo() { return rollNo; }
        public int getCurrentSemester() { return currentSemester; }
        public int getEntrySemester() { return entrySemester; }
        public String getOutcome() { return outcome; }
    }
}
