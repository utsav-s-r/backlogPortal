package com.college.backlog.controller.dto;

import com.college.backlog.model.BatchLine;
import com.college.backlog.model.ExamCycle;

import java.time.Instant;
import java.util.List;

/**
 * A cycle row plus {@code referenced}: does any registration point at this cycle. The server
 * refuses an edit once one does, and unlike a subject EVERY editable field here (name, month) is
 * one the registrations table and the printed form read live — so a referenced cycle offers no
 * edit at all, and the list has to say which rows those are.
 *
 * <p>A DTO rather than a transient field on {@link ExamCycle}: the flag belongs to the list query,
 * and create/activate/deactivate return the same entity without ever checking it.
 */
public record ExamCycleListItem(
        Long id,
        String name,
        String examMonthYear,
        boolean active,
        Instant createdAt,
        List<BatchLine> batchLines,
        boolean referenced) {

    public static ExamCycleListItem of(ExamCycle cycle, boolean referenced) {
        return new ExamCycleListItem(
                cycle.getId(),
                cycle.getName(),
                cycle.getExamMonthYear(),
                cycle.isActive(),
                cycle.getCreatedAt(),
                cycle.getBatchLines(),
                referenced);
    }
}
