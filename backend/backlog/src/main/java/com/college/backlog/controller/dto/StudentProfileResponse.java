package com.college.backlog.controller.dto;

import java.util.List;

// Read-only profile for the student dashboard; omits DOB (a login secret) and password fields.
public class StudentProfileResponse {
    private String rollNo;
    private String name;
    private String email;
    private String branch;
    private String phone;          // may be null until the student sets it
    private int currentSemester;
    // backlog semesters registerable, derived from currentSemester
    private List<Integer> eligibleSemesters;

    public StudentProfileResponse(String rollNo, String name, String email,
                                  String branch, String phone, int currentSemester,
                                  List<Integer> eligibleSemesters) {
        this.rollNo = rollNo;
        this.name = name;
        this.email = email;
        this.branch = branch;
        this.phone = phone;
        this.currentSemester = currentSemester;
        this.eligibleSemesters = eligibleSemesters;
    }

    public String getRollNo() { return rollNo; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getBranch() { return branch; }
    public String getPhone() { return phone; }
    public int getCurrentSemester() { return currentSemester; }
    public List<Integer> getEligibleSemesters() { return eligibleSemesters; }
}
