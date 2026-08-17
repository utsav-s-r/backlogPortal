package com.college.backlog.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit payload for an existing subject. {@code academicYearOffered} and the department are
 * intentionally absent: the year is the binding key with the code prefix locked to it, and a
 * subject can't be reassigned. Only suffix/name/credits/semester/type/eligibility change.
 */
public class SubjectUpdateRequest {
    @NotBlank(message = "Subject name cannot be empty")
    private String subjectName;

    @NotBlank(message = "Course code cannot be empty")
    private String courseCode;

    @Min(value = 1, message = "Semester must be between 1 and 8")
    @Max(value = 8, message = "Semester must be between 1 and 8")
    private int semester;

    @Min(value = 0, message = "Credits cannot be negative")
    private int credits;

    private String subjectType = "REGULAR";

    private List<Long> eligibleDeptIds = new ArrayList<>();

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
