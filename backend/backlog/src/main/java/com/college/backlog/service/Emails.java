package com.college.backlog.service;

/**
 * The student email rule. Created as {@code <usn>@msrit.edu}; afterwards staff or the student may
 * set any address. Blank resets to the institutional one, so a student always has an address.
 * Any domain is accepted — format only. Mirror: frontend {@code src/lib/email.js}.
 */
public final class Emails {

    /** Deliberately loose: something@something.tld, no whitespace. */
    public static final String REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
    /** RFC 5321 path limit; also keeps it under {@code students.email varchar(255)}, which would
     *  otherwise surface as a 500 (a DB constraint, not 23505). */
    public static final int MAX_LENGTH = 254;
    public static final String MESSAGE = "Enter a valid email address.";

    private Emails() {}

    public static String institutional(String rollNo) {
        return rollNo.toLowerCase() + "@msrit.edu";
    }

    /**
     * Blank ⇒ the institutional address; anything else must be a valid address.
     * @throws IllegalArgumentException malformed or too long — wrapped as a 400 by HTTP callers.
     */
    public static String normalize(String raw, String rollNo) {
        String e = StudentManagementService.trimToNull(raw);
        if (e == null) {
            return institutional(rollNo);
        }
        if (e.length() > MAX_LENGTH || !e.matches(REGEX)) {
            throw new IllegalArgumentException(MESSAGE);
        }
        return e;
    }
}
