package com.college.backlog.controller.dto;

import java.util.List;

// The generated clone draft: one row per source subject with course code and academic year
// already bumped to the target, plus a status flag so the UI can show created vs skipped.
public class SubjectClonePreviewResponse {
    private int sourceYear;
    private int targetYear;
    private Long deptId;
    private List<Row> rows;

    public SubjectClonePreviewResponse(int sourceYear, int targetYear, Long deptId, List<Row> rows) {
        this.sourceYear = sourceYear;
        this.targetYear = targetYear;
        this.deptId = deptId;
        this.rows = rows;
    }

    public int getSourceYear() { return sourceYear; }
    public int getTargetYear() { return targetYear; }
    public Long getDeptId() { return deptId; }
    public List<Row> getRows() { return rows; }

    public static class Row {
        private String subjectName;
        private String courseCode;
        private int semester;
        private int credits;
        private String subjectType;
        private List<Long> eligibleDeptIds;
        private String status;   // WOULD_CREATE | WOULD_SKIP
        private String message;

        public Row(String subjectName, String courseCode, int semester, int credits,
                   String subjectType, List<Long> eligibleDeptIds, String status, String message) {
            this.subjectName = subjectName;
            this.courseCode = courseCode;
            this.semester = semester;
            this.credits = credits;
            this.subjectType = subjectType;
            this.eligibleDeptIds = eligibleDeptIds;
            this.status = status;
            this.message = message;
        }

        public String getSubjectName() { return subjectName; }
        public String getCourseCode() { return courseCode; }
        public int getSemester() { return semester; }
        public int getCredits() { return credits; }
        public String getSubjectType() { return subjectType; }
        public List<Long> getEligibleDeptIds() { return eligibleDeptIds; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
    }
}
