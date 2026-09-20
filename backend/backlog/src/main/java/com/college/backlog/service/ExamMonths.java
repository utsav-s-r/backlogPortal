package com.college.backlog.service;

import java.time.Month;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An exam cycle's month is stored canonically as {@code YYYY-MM} (ExamCycleRequest's
 * {@code @Pattern}); "June 2026" is presentation, built here. Sibling of {@link AcademicYears} and
 * {@link Semesters}, and the same split the academic year already uses.
 *
 * <p>The UI has its own copy in {@code frontend/src/lib/examMonth.js} for the cycles table. Two
 * copies because the PDF is rendered server-side and the table client-side — keep them in step,
 * or one screen labels a cycle differently from the form printed for it.
 */
public final class ExamMonths {

    private static final Pattern CANONICAL = Pattern.compile("^(\\d{4})-(0[1-9]|1[0-2])$");

    private ExamMonths() {}

    /**
     * {@code "2026-06"} -> {@code "June 2026"}. Anything else is returned TRIMMED AND UNCHANGED:
     * cycles created before the format existed hold free text ("Not a valid month/year"), and no
     * rule recovers a month from that — printing it verbatim is visibly wrong on the form, where
     * inventing a plausible month would not be. {@code ""} for null or blank.
     */
    public static String format(String stored) {
        String raw = stored == null ? "" : stored.trim();
        Matcher match = CANONICAL.matcher(raw);
        if (!match.matches()) {
            return raw;
        }
        // getDisplayName would follow the server's locale; the form is English and its month must
        // not change with the host's default locale.
        String month = Month.of(Integer.parseInt(match.group(2))).name();
        return month.charAt(0) + month.substring(1).toLowerCase() + " " + match.group(1);
    }
}
