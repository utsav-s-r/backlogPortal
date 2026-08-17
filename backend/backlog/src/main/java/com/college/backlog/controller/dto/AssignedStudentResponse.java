package com.college.backlog.controller.dto;

// One supervised student in a proctor's assignment list (management view).
public class AssignedStudentResponse {
    private final String rollNo;
    private final String name;
    private final int currentSemester;
    private final String assignedBy;
    private final String assignedAt;

    public AssignedStudentResponse(String rollNo, String name, int currentSemester,
                                   String assignedBy, String assignedAt) {
        this.rollNo = rollNo;
        this.name = name;
        this.currentSemester = currentSemester;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }

    public String getRollNo() { return rollNo; }
    public String getName() { return name; }
    public int getCurrentSemester() { return currentSemester; }
    public String getAssignedBy() { return assignedBy; }
    public String getAssignedAt() { return assignedAt; }
}
