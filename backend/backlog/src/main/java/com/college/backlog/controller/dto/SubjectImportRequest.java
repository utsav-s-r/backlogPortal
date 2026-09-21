package com.college.backlog.controller.dto;

import java.util.List;

/**
 * A CSV subject import. The academic year is BATCH-LEVEL and stamped on every row server-side —
 * there is no per-row year, and there must not be one: the year is the binding key and year-binding
 * is fail-closed, so a mistyped year produces a subject that looks correct in the admin catalog and
 * is invisible to every student who needs it. One year per file, typed once. Same rule as
 * {@code SubjectCloneService}'s targetYear.
 *
 * <p>{@code deptId} is honoured only for an unrestricted ADMIN/PRINCIPAL; a dept-pinned caller has
 * their own department forced over it.
 *
 * <p>No Bean Validation annotations, matching the six sibling request DTOs: {@code deptId} is
 * required-or-ignored depending on the caller's role and {@code academicYearOffered}'s upper bound
 * is relative to now, neither of which an annotation can express. Shape via annotations, context in
 * the service — an {@code @AssertTrue} here would hide role-shaped logic where the auth audit does
 * not look.
 */
public class SubjectImportRequest {

    private List<Row> rows;
    private Long deptId;
    private int academicYearOffered;
    private boolean dryRun;

    public List<Row> getRows() { return rows; }
    public void setRows(List<Row> rows) { this.rows = rows; }

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }

    public int getAcademicYearOffered() { return academicYearOffered; }
    public void setAcademicYearOffered(int academicYearOffered) { this.academicYearOffered = academicYearOffered; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    /**
     * One CSV line. {@code semester} and {@code credits} are boxed so a blank cell arrives as null
     * and is reported as missing, rather than as a 0 that would file the subject under semester 0.
     *
     * <p>Eligible departments arrive as CODES ("CS"), not ids: ids are {@code @GeneratedValue} and
     * differ between the dev and production databases, so a file authored against one would import
     * silently wrong against the other.
     */
    public static class Row {
        private String subjectName;
        private String courseCode;
        private Integer semester;
        private Integer credits;
        private String subjectType;
        private List<String> eligibleDeptCodes;

        public String getSubjectName() { return subjectName; }
        public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

        public String getCourseCode() { return courseCode; }
        public void setCourseCode(String courseCode) { this.courseCode = courseCode; }

        public Integer getSemester() { return semester; }
        public void setSemester(Integer semester) { this.semester = semester; }

        public Integer getCredits() { return credits; }
        public void setCredits(Integer credits) { this.credits = credits; }

        public String getSubjectType() { return subjectType; }
        public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

        public List<String> getEligibleDeptCodes() { return eligibleDeptCodes; }
        public void setEligibleDeptCodes(List<String> eligibleDeptCodes) { this.eligibleDeptCodes = eligibleDeptCodes; }
    }
}
