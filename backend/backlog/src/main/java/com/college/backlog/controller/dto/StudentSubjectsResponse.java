package com.college.backlog.controller.dto;

import com.college.backlog.model.Subject;

import java.util.List;

// Subjects available for one backlog semester, plus the academic year it resolved to (from the
// progression record). The year is surfaced so the UI can show it read-only — the student does not
// choose it. See docs/adr/backlog-progression.md.
public class StudentSubjectsResponse {
    private int semester;
    private int academicYear;
    private List<Subject> subjects;

    public StudentSubjectsResponse(int semester, int academicYear, List<Subject> subjects) {
        this.semester = semester;
        this.academicYear = academicYear;
        this.subjects = subjects;
    }

    public int getSemester() { return semester; }
    public int getAcademicYear() { return academicYear; }
    public List<Subject> getSubjects() { return subjects; }
}
