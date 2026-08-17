package com.college.backlog.model;

/**
 * The action an audit row in {@link RegistrationEvent} records — the registration lifecycle
 * transitions, a subset of {@link RegistrationStatus} names, kept as its own type so the audit log
 * stays decoupled. Persisted via {@code @Enumerated(EnumType.STRING)}, guarded by a DB CHECK.
 */
public enum EventAction {
    SUBMITTED,
    VERIFIED,
    REJECTED
}
