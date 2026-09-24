package com.college.backlog.controller.dto;

/** The audience as it stands NOW (it is re-resolved at send time) and one rendered email. */
public record ReminderPreview(int recipientCount, String sampleTo, String sampleSubject, String sampleBody) {}
