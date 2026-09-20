package com.college.backlog.controller.dto;

import java.util.List;

public class RegistrationSummaryResponse {
    private String regId;
    private String rollNo;
    private String studentName;
    private int semester;
    private int yearOfJoining;
    private List<String> subjects;
    private String status;
    private String registeredAt;
    private String verifiedBy;
    private String examCycle;
    /** May THIS caller action this row? Every involved department sees a registration, but only
     *  the student's own may verify or reject it, so the list has to say which rows carry the
     *  buttons — otherwise the UI offers an action that 403s on click. */
    private boolean canVerify;

    public RegistrationSummaryResponse(String regId, String rollNo, String studentName, int semester, int yearOfJoining, List<String> subjects, String status, String registeredAt, String verifiedBy, String examCycle, boolean canVerify) {
        this.regId = regId;
        this.rollNo = rollNo;
        this.studentName = studentName;
        this.semester = semester;
        this.yearOfJoining = yearOfJoining;
        this.subjects = subjects;
        this.status = status;
        this.registeredAt = registeredAt;
        this.verifiedBy = verifiedBy;
        this.examCycle = examCycle;
        this.canVerify = canVerify;
    }

    public String getRegId() { return regId; }
    public String getRollNo() { return rollNo; }
    public String getStudentName() { return studentName; }
    public int getSemester() { return semester; }
    public int getYearOfJoining() { return yearOfJoining; }
    public List<String> getSubjects() { return subjects; }
    public String getStatus() { return status; }
    public String getRegisteredAt() { return registeredAt; }
    public String getVerifiedBy() { return verifiedBy; }
    public String getExamCycle() { return examCycle; }
    public boolean isCanVerify() { return canVerify; }
}