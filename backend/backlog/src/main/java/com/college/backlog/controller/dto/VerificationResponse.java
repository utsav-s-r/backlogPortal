package com.college.backlog.controller.dto;

public class VerificationResponse {
    private String regId;
    private String studentName;
    private String rollNo;
    private String status;

    public VerificationResponse() {}

    public VerificationResponse(String regId, String studentName, String rollNo, String status) {
        this.regId = regId;
        this.studentName = studentName;
        this.rollNo = rollNo;
        this.status = status;
    }

    public String getRegId() { return regId; }
    public void setRegId(String regId) { this.regId = regId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
