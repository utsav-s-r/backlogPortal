package com.college.backlog.controller.dto;

import java.time.Instant;

public record ReminderFailureItem(String rollNo, String email, String error, Instant processedAt) {}
