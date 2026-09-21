package com.college.backlog.controller.dto;

import com.college.backlog.model.Department;

/**
 * A department row plus two derived flags, each one query for the whole page.
 *
 * <p>{@code hasStudents} — does any student carry this branch code. The code is FIXED once they
 * do: it is the branch segment of every one of their USNs ({@code 1MS24CS001}), and since
 * 2026-09-20 it is also what decides which department may verify their registrations. The server
 * refuses the change; this flag is what lets the page lock the field instead of taking a rename
 * and failing it on save.
 *
 * <p>{@code hasVerifier} — does the department hold an account that may verify its students'
 * registrations. Reported, never enforced: a department is allowed to have students and no staff,
 * and any setup order must work. Without the flag the gap is invisible everywhere — the student
 * sees "pending", the row says the student's department verifies, and no one in that department
 * exists to look. ADMIN can still verify college-wide, so this is a stall, not a dead end.
 *
 * <p>Field names mirror the entity exactly, so the flags are purely additive for the client —
 * {@code version} included, which the edit form sends back for the stale-overwrite check.
 */
public record DepartmentListItem(
        Long id,
        String deptName,
        String code,
        String contactEmail,
        Long version,
        boolean hasStudents,
        boolean hasVerifier) {

    public static DepartmentListItem of(Department department, boolean hasStudents,
                                        boolean hasVerifier) {
        return new DepartmentListItem(
                department.getId(),
                department.getDeptName(),
                department.getCode(),
                department.getContactEmail(),
                department.getVersion(),
                hasStudents,
                hasVerifier);
    }
}
