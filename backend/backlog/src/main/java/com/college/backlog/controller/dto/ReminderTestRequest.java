package com.college.backlog.controller.dto;

/** Send one sample to {@code to}, with placeholder student data — no real student's details leave. */
public class ReminderTestRequest {

    private String to;
    private String subject;
    private String message;

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
