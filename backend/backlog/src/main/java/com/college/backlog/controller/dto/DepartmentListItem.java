package com.college.backlog.controller.dto;

import com.college.backlog.model.Department;

/**
 * A department row plus {@code hasStudents}: does any student carry this branch code.
 *
 * <p>The code is FIXED once they do — it is the branch segment of every one of their USNs
 * ({@code 1MS24CS001}), and since 2026-09-20 it is also what decides which department may verify
 * their registrations. The server refuses the change; this flag is what lets the page lock the
 * field instead of taking a rename and failing it on save.
 *
 * <p>Field names mirror the entity exactly, so the flag is purely additive for the client —
 * {@code version} included, which the edit form sends back for the stale-overwrite check.
 */
public record DepartmentListItem(
        Long id,
        String deptName,
        String code,
        String contactEmail,
        Long version,
        boolean hasStudents) {

    public static DepartmentListItem of(Department department, boolean hasStudents) {
        return new DepartmentListItem(
                department.getId(),
                department.getDeptName(),
                department.getCode(),
                department.getContactEmail(),
                department.getVersion(),
                hasStudents);
    }
}
