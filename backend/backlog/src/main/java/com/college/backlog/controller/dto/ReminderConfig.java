package com.college.backlog.controller.dto;

/** What the page shows about delivery. Never the API key or the cron token — only whether set. */
public record ReminderConfig(boolean mailConfigured, String senderEmail, String senderName,
                             boolean triggerConfigured, int dailyCap) {}
