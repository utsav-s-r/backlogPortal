package com.college.backlog.model;

/** What kind of thing an {@link AdminAuditEvent} is about. Pairs with its {@code targetId}. */
public enum AuditTargetType {
    USER, EXAM_CYCLE, SUBJECT, DEPARTMENT, EXPORT, PROCTOR_ASSIGNMENT
}
