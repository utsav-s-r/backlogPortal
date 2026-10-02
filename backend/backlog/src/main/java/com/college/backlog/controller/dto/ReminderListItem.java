package com.college.backlog.controller.dto;

import com.college.backlog.model.Reminder;

import java.time.Instant;

public record ReminderListItem(Long id, Long examCycleId, String examCycleName, String departmentCode,
                               String subject, String message, Instant sendAt, String status,
                               String createdBy, Instant createdAt, Instant completedAt,
                               String cancelledBy, Instant cancelledAt, String lastError,
                               long sent, long failed) {

    public static ReminderListItem of(Reminder r, long sent, long failed) {
        return new ReminderListItem(r.getId(), r.getExamCycle().getId(), r.getExamCycle().getName(),
                r.getDepartmentCode(), r.getSubject(), r.getMessage(), r.getSendAt(),
                r.getStatus().name(), r.getCreatedBy(), r.getCreatedAt(), r.getCompletedAt(),
                r.getCancelledBy(), r.getCancelledAt(), r.getLastError(), sent, failed);
    }
}
