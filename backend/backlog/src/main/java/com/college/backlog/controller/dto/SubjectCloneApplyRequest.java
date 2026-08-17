package com.college.backlog.controller.dto;

import java.util.List;

// The admin-approved (possibly edited) draft to commit. academicYearOffered is NOT read from the
// rows — the server forces targetYear — and deptId is re-validated against the caller's scope, so
// rows carry only editable fields.
public class SubjectCloneApplyRequest {
    private Long deptId;
    private int targetYear;
    private List<Row> rows;

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }

    public int getTargetYear() { return targetYear; }
    public void setTargetYear(int targetYear) { this.targetYear = targetYear; }

    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }

    public static class Row {
        private String subjectName;
        private String courseCode;
        private int semester;
        private int credits;
        private String subjectType = "REGULAR";
        private List<Long> eligibleDeptIds;

        public String getSubjectName() { return subjectName; }
        public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

        public String getCourseCode() { return courseCode; }
        public void setCourseCode(String courseCode) { this.courseCode = courseCode; }

        public int getSemester() { return semester; }
        public void setSemester(int semester) { this.semester = semester; }

        public int getCredits() { return credits; }
        public void setCredits(int credits) { this.credits = credits; }

        public String getSubjectType() { return subjectType; }
        public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

        public List<Long> getEligibleDeptIds() { return eligibleDeptIds; }
        public void setEligibleDeptIds(List<Long> eligibleDeptIds) { this.eligibleDeptIds = eligibleDeptIds; }
    }
}
