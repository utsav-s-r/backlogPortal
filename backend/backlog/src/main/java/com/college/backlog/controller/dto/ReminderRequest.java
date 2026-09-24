package com.college.backlog.controller.dto;

/**
 * Schedule (and preview) a reminder. {@code sendAt} is the college's wall clock,
 * {@code yyyy-MM-ddTHH:mm} in Asia/Kolkata — converted server-side, so the admin's browser zone
 * cannot shift it. Preview ignores it. Validated in ReminderService, not by annotation: preview
 * and create share the type but not the rules.
 */
public class ReminderRequest {

    private Long examCycleId;
    /** Null = every department. */
    private Long departmentId;
    private String sendAt;
    private String subject;
    private String message;

    public Long getExamCycleId() { return examCycleId; }
    public void setExamCycleId(Long examCycleId) { this.examCycleId = examCycleId; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public String getSendAt() { return sendAt; }
    public void setSendAt(String sendAt) { this.sendAt = sendAt; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
