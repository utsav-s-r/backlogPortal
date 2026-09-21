package com.college.backlog.service;

/**
 * The student phone format: exactly 10 digits. Optional on the account, required to register, so
 * every writer must refuse a malformed value — otherwise it saves, shows as set, and registration
 * 409s telling the student to add a phone they can see is already there.
 * Mirror: frontend {@code src/lib/phone.js}.
 */
public final class Phones {

    /** Compile-time constant so {@code @Pattern} can reference it. */
    public static final String REGEX = "^[0-9]{10}$";
    public static final String MESSAGE = "Phone number must be exactly 10 digits.";

    private Phones() {}

    public static boolean isValid(String phone) {
        return phone != null && phone.matches(REGEX);
    }

    /**
     * Blank ⇒ null (phone is optional); anything else must be valid.
     * @throws IllegalArgumentException malformed — wrapped as a 400 / row ERROR by HTTP callers.
     */
    public static String normalizeOptional(String raw) {
        String p = StudentManagementService.trimToNull(raw);
        if (p != null && !isValid(p)) {
            throw new IllegalArgumentException(MESSAGE);
        }
        return p;
    }
}
