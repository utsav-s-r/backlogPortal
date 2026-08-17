package com.college.backlog.model;

import java.util.Locale;

/**
 * Kind of {@link Subject} offering: ELECTIVE carries an eligible-department list, REGULAR belongs
 * to one department. Persisted via {@code @Enumerated(EnumType.STRING)}, guarded by a DB CHECK.
 */
public enum SubjectType {
    REGULAR,
    ELECTIVE;

    /** Case-insensitive parse; {@code null} for null/blank/unknown. Used at the HTTP boundary,
     *  where the value arrives as a plain String (filters, create requests). */
    public static SubjectType fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SubjectType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
