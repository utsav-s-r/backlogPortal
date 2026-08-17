package com.college.backlog.controller.dto;

// Correct the academic year a student studied a semester (overwrites; audited).
public class ProgressionOverrideRequest {
    private int academicYear;

    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }
}
