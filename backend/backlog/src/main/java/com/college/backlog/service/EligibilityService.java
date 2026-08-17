package com.college.backlog.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Backlog semester-eligibility rule: every semester the student has studied here, from entry
 * through the current semester. A backlog is never retired by moving up a year.
 *
 * <pre>
 *   eligible = { max(entrySemester, 1) .. currentSem }
 * </pre>
 *
 * Normal intake (entrySemester = 1): 2-{1,2} 4-{1..4} 6-{1..6} 8-{1..8}. {@code entrySemester}
 * raises the floor for a lateral/migrant student so semesters they never studied here are never
 * offered (entry 3, current 6 -&gt; {3,4,5,6}).
 *
 * Superseded the earlier retiring window (floor 1/3/5 by year), which dropped a backlog out of
 * reach once the student moved up two years — owner decision 2026-08-17. Registering an old
 * semester still fails closed on year-binding if that year's offering isn't in the catalog.
 *
 * {@code currentSemester} is even (2..8) and {@code entrySemester} odd (1..7) by
 * {@link Semesters}, but this stays a pure function of whatever it is handed — parity is
 * validated at the write path, not re-asserted here.
 *
 * Pure function of the admin-maintained current and entry semesters — see
 * docs/adr/backlog-progression.md.
 */
@Service
public class EligibilityService {

    /**
     * Semesters the student may register backlogs for, ascending. Empty if out of range.
     * No one-arg overload — entry is never implicit, since defaulting it silently widens a lateral
     * entrant's window to semesters they never studied here ({@code 1} for a normal intake).
     */
    public Set<Integer> eligibleSemesters(int currentSemester, int entrySemester) {
        Set<Integer> eligible = new LinkedHashSet<>();
        if (currentSemester < 1 || currentSemester > 8) {
            return eligible;
        }
        // a lateral entrant's window starts no earlier than the semester they joined in
        int floor = Math.max(entrySemester, 1);
        for (int sem = floor; sem <= currentSemester; sem++) {
            eligible.add(sem);
        }
        return eligible;
    }

    /**
     * Whether {@code targetSemester} is in the student's backlog window. There is deliberately no
     * two-arg overload — {@code isEligible(current, target)} reads as if arg 2 were the entry
     * semester — so always pass it explicitly ({@code 1} for a normal intake).
     */
    public boolean isEligible(int currentSemester, int entrySemester, int targetSemester) {
        return eligibleSemesters(currentSemester, entrySemester).contains(targetSemester);
    }
}
