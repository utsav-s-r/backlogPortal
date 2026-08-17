package com.college.backlog.controller.dto;

// Per-row outcome of a bulk op (student import, proctor claim).
// status: CREATED | SKIPPED_EXISTS | ERROR | WOULD_CREATE | WOULD_SKIP.
public class ProgressionRowResult {
    private String rollNo;
    private Integer semester;
    private String status;
    private String message;

    public ProgressionRowResult(String rollNo, Integer semester, String status, String message) {
        this.rollNo = rollNo;
        this.semester = semester;
        this.status = status;
        this.message = message;
    }

    public String getRollNo() { return rollNo; }
    public Integer getSemester() { return semester; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
}
