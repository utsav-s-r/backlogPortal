package com.college.backlog.model;

/**
 * Lifecycle state of a {@link Registration}. Persisted via
 * {@code @Enumerated(EnumType.STRING)} — the column stays a varchar, with a matching DB CHECK
 * constraint guarding the values.
 */
public enum RegistrationStatus {
    SUBMITTED,
    VERIFIED,
    REJECTED
}
