package com.college.backlog.controller.dto;

import java.util.List;

// Generate a clone draft: a department's sourceYear subjects, bumped to targetYear.
// semesters is optional — null/empty means all of 1..8.
public class SubjectClonePreviewRequest {
    private Long deptId;
    private int sourceYear;
    private int targetYear;
    private List<Integer> semesters;

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }

    public int getSourceYear() { return sourceYear; }
    public void setSourceYear(int sourceYear) { this.sourceYear = sourceYear; }

    public int getTargetYear() { return targetYear; }
    public void setTargetYear(int targetYear) { this.targetYear = targetYear; }

    public List<Integer> getSemesters() { return semesters; }
    public void setSemesters(List<Integer> semesters) { this.semesters = semesters; }
}
