package com.college.backlog.controller.dto;

/**
 * Roster row for the admin Students list. Omits dateOfBirth deliberately — it is the student login
 * credential and write-only, never returned by any endpoint.
 */
public class StudentSummaryResponse {
    private final String rollNo;
    private final String name;
    private final String email;
    private final String phone;
    private final String branch;
    private final int currentSemester;
    private final int entrySemester;

    public StudentSummaryResponse(String rollNo, String name, String email, String phone,
                                  String branch, int currentSemester, int entrySemester) {
        this.rollNo = rollNo;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.branch = branch;
        this.currentSemester = currentSemester;
        this.entrySemester = entrySemester;
    }

    public String getRollNo() { return rollNo; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getBranch() { return branch; }
    public int getCurrentSemester() { return currentSemester; }
    public int getEntrySemester() { return entrySemester; }
}
