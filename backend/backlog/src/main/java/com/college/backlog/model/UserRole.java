package com.college.backlog.model;

import java.util.Locale;

/**
 * Role of an admin-side {@link User}. The names double as Spring Security authorities (no
 * {@code ROLE_} prefix) and the JWT carries {@code role.name()}. Persisted via
 * {@code @Enumerated(EnumType.STRING)}, guarded by a DB CHECK constraint.
 *
 * STUDENT is deliberately absent — students are a separate table and auth scope with no User row.
 * The audit log's wider actor set is {@link ActorRole}.
 */
public enum UserRole {
    ADMIN,
    PRINCIPAL,
    HOD,
    DEPT_OFFICE,
    // Dept-pinned like HOD/DEPT_OFFICE, plus scoped to an explicit assigned-student set
    // (proctor_students). See ProctorScopeService.
    PROCTOR;

    /** Case-insensitive parse; {@code null} for null/blank/unknown. Used at the HTTP boundary
     *  (user-management create) to reject bad roles with a 400. */
    public static UserRole fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
