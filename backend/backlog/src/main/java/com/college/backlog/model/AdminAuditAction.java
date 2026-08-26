package com.college.backlog.model;

/**
 * What a privileged staff member did. Grouped by the priority order in the P3-9 finding: the
 * privilege-granting surface first, then the college-wide switch, then data egress, then catalog
 * and org changes.
 *
 * <p>Deliberately NOT constrained by a DB CHECK (see V3): this vocabulary grows whenever an audited
 * operation is added, and a CHECK would force a migration each time. This enum is the control.
 */
public enum AdminAuditAction {
    // 1. privilege surface
    USER_CREATE, USER_DELETE, USER_PASSWORD_RESET,
    // 2. the college-wide registration switch
    EXAM_CYCLE_CREATE, EXAM_CYCLE_ACTIVATE, EXAM_CYCLE_DEACTIVATE,
    // 3. personal data leaving the system
    PDF_BULK_EXPORT,
    // 4. catalog and org structure
    SUBJECT_CREATE, SUBJECT_UPDATE, SUBJECT_DELETE, SUBJECT_CLONE,
    DEPARTMENT_CREATE, DEPARTMENT_UPDATE, DEPARTMENT_DELETE,
    PROCTOR_UNASSIGN
}
