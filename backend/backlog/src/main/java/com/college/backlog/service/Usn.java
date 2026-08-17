package com.college.backlog.service;

/**
 * The USN (roll-number) format and its derivations. A USN is {@code 1MS<YY><BR><NNN>} — e.g.
 * {@code 1MS22CS001}: fixed {@code 1MS} prefix, two-digit admission year, two-letter branch code,
 * three-digit serial.
 *
 * Single source of truth for that rule — validation and year/branch slicing were previously
 * copy-pasted inline across the registration, student, and progression paths. Route every caller
 * through here so the format can't drift between endpoints. See docs/adr/backlog-progression.md.
 */
public final class Usn {

    private Usn() {}

    /** Canonical USN pattern, {@code 1MS<YY><BR><NNN>}. */
    public static final String REGEX = "^1MS\\d{2}[A-Za-z]{2}\\d{3}$";

    /** Whether the roll number is a well-formed USN. */
    public static boolean isValid(String rollNo) {
        return rollNo != null && rollNo.matches(REGEX);
    }

    /** Uppercase two-letter branch code (e.g. {@code CS}), or null if malformed. */
    public static String branchCode(String rollNo) {
        return isValid(rollNo) ? rollNo.substring(5, 7).toUpperCase() : null;
    }

    /** Full admission year from the two-digit {@code YY} (e.g. 2022), or -1 if malformed. */
    public static int admissionYear(String rollNo) {
        return isValid(rollNo) ? 2000 + Integer.parseInt(rollNo.substring(3, 5)) : -1;
    }
}
