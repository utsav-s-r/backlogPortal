package com.college.backlog.controller.dto;

import com.college.backlog.model.Department;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;

import java.util.List;

/**
 * A catalog row plus {@code registered}: does any registration reference this subject. The server
 * refuses an edit to the four printed fields once it does
 * ({@code SubjectService.assertPrintedFieldsUnchangedOnceRegistered}), and without this flag the
 * UI can only discover that by submitting and failing.
 *
 * <p>A DTO rather than a transient field on {@link Subject}: the flag is a property of the LIST
 * query, not of the entity, and the create/update/clone responses return the same entity — a
 * {@code registered:false} there would state something none of them checked.
 *
 * <p>Component names mirror the entity's JSON exactly, so the flag is purely additive for the
 * client. {@code department} and {@code eligibleDepartments} are the entity's own EAGER objects,
 * serialized as they were before.
 */
public record SubjectListItem(
        Long id,
        String subjectName,
        String courseCode,
        int semester,
        int credits,
        int academicYearOffered,
        SubjectType subjectType,
        Department department,
        List<Department> eligibleDepartments,
        boolean registered) {

    public static SubjectListItem of(Subject subject, boolean registered) {
        return new SubjectListItem(
                subject.getId(),
                subject.getSubjectName(),
                subject.getCourseCode(),
                subject.getSemester(),
                subject.getCredits(),
                subject.getAcademicYearOffered(),
                subject.getSubjectType(),
                subject.getDepartment(),
                subject.getEligibleDepartments(),
                registered);
    }
}
