package com.college.backlog.model;

/**
 * Who performed a {@link RegistrationEvent}: a STUDENT (on submit) or an admin {@link UserRole}
 * (on verify/reject). A superset of UserRole by the single STUDENT value, kept as its own type so
 * the append-only audit log isn't coupled to the User role set. Persisted via
 * {@code @Enumerated(EnumType.STRING)}, guarded by a DB CHECK.
 */
public enum ActorRole {
    STUDENT,
    ADMIN,
    PRINCIPAL,
    HOD,
    DEPT_OFFICE,
    PROCTOR
}
