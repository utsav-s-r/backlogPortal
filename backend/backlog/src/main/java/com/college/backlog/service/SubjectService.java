package com.college.backlog.service;

import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.model.*;
import com.college.backlog.controller.dto.SubjectUpdateRequest;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.SubjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
public class SubjectService {

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RegistrationRepository registrationRepository;

    @Transactional
    public Subject createSubject(SubjectCreateRequest request) {
        // Authoritative year check — must run BEFORE the prefix check below, which only compares
        // the code against whatever year was sent and so accepts any absurd year with a matching
        // prefix (year 0 + "00CS44", year 9999 + "99CS44" both pass it). The DTO's @Min is only a
        // floor; the upper bound is relative to now and can't be expressed as an annotation.
        try {
            AcademicYears.assertInRange(request.getAcademicYearOffered());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // prefix=year invariant: the code's first two digits are the academic-year start. The UI
        // locks the prefix; this is the server backstop against a crafted request.
        if (!CourseCodes.matchesYear(request.getCourseCode(), request.getAcademicYearOffered())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Course code must start with the academic year's two digits ("
                    + CourseCodes.prefixForYear(request.getAcademicYearOffered()) + ").");
        }

        // 400, not 404: the deptId comes from the REQUEST BODY, so the resource addressed by the
        // URL exists and it is the submitted reference that is wrong. Matches the three sibling
        // body-referenced lookups (SubjectClone, StudentManagement, Progression).
        Department department = departmentRepository.findById(request.getDeptId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department."));

        // Setters, not an all-args constructor — semester/credits/academicYearOffered are three
        // ints and a positional swap would compile silently. id is left to @GeneratedValue.
        Subject subject = new Subject();
        subject.setSubjectName(request.getSubjectName());
        subject.setCourseCode(request.getCourseCode());
        subject.setSemester(request.getSemester());
        subject.setCredits(request.getCredits());
        subject.setAcademicYearOffered(request.getAcademicYearOffered());
        subject.setDepartment(department);

        SubjectType type = resolveSubjectType(request.getSubjectType());
        subject.setSubjectType(type);

        if (type == SubjectType.ELECTIVE && request.getEligibleDeptIds() != null && !request.getEligibleDeptIds().isEmpty()) {
            subject.setEligibleDepartments(resolveEligibleDepartments(request.getEligibleDeptIds()));
        }

        return subjectRepository.save(subject);
    }

    /**
     * Edit a subject. The academic year is NOT editable (it is the binding key), so the
     * course-code prefix stays locked to it. callerDeptId is non-null for HOD/DEPT_OFFICE, who may
     * only touch their own department.
     */
    @Transactional
    public Subject updateSubject(Long id, SubjectUpdateRequest request, Long callerDeptId) {
        Subject subject = subjectRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subject not found with ID: " + id));

        if (callerDeptId != null
                && (subject.getDepartment() == null || !callerDeptId.equals(subject.getDepartment().getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You can only edit subjects for your own department.");
        }

        // year is fixed, so the prefix must still match it — suffix-only edits
        if (!CourseCodes.matchesYear(request.getCourseCode(), subject.getAcademicYearOffered())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Course code must start with the academic year's two digits ("
                    + CourseCodes.prefixForYear(subject.getAcademicYearOffered()) + ").");
        }

        subject.setSubjectName(request.getSubjectName());
        subject.setCourseCode(request.getCourseCode());
        subject.setSemester(request.getSemester());
        subject.setCredits(request.getCredits());

        SubjectType type = resolveSubjectType(request.getSubjectType());
        subject.setSubjectType(type);
        if (type == SubjectType.ELECTIVE
                && request.getEligibleDeptIds() != null && !request.getEligibleDeptIds().isEmpty()) {
            subject.setEligibleDepartments(resolveEligibleDepartments(request.getEligibleDeptIds()));
        } else {
            subject.setEligibleDepartments(new ArrayList<>());
        }

        try {
            return subjectRepository.saveAndFlush(subject);
        } catch (DataIntegrityViolationException e) {
            // Only the code+year unique index means "duplicate". Any other violation used to be
            // reported as one too, which is a false explanation of someone else's problem — let it
            // fall through to GlobalExceptionHandler's generic 409, which also logs it.
            if (!Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)) {
                throw e;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Another subject with course code '" + request.getCourseCode()
                    + "' already exists for this academic year.");
        }
    }

    /** Delete a subject, dept-scoped; blocked if any registration references it, since removing
     *  it out from under a registration would corrupt that record. */
    @Transactional
    public void deleteSubject(Long id, Long callerDeptId) {
        Subject subject = subjectRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subject not found with ID: " + id));

        if (callerDeptId != null
                && (subject.getDepartment() == null || !callerDeptId.equals(subject.getDepartment().getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You can only delete subjects for your own department.");
        }
        if (registrationRepository.existsBySubjects_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This subject is referenced by existing registrations and cannot be deleted.");
        }
        subjectRepository.delete(subject);
    }

    /**
     * Options for the admin list's subject dropdown: the distinct subjects actually registered for
     * under the caller's other filters. Mirrors RegistrationSpecification's predicates — keep the
     * two in step, or the dropdown offers a subject that yields no rows.
     *
     * @param studentRollNos proctor scope; null = unrestricted. An EMPTY set means "assigned to
     *     nobody" and callers must short-circuit — an empty IN list is not valid SQL, and omitting
     *     the predicate would leak every department's subjects to a proctor.
     */
    public List<Subject> findDistinctSubjectsByRegistrationFilters(
            Long departmentId, String subjectType, String searchQuery, Integer semester,
            Collection<String> studentRollNos) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Subject> query = cb.createQuery(Subject.class);
        Root<Registration> registrationRoot = query.from(Registration.class);
        Join<Registration, Subject> subjectJoin = registrationRoot.join("subjects");

        query.select(subjectJoin).distinct(true);

        List<Predicate> predicates = new ArrayList<>();
        boolean needsStudent = (searchQuery != null && !searchQuery.isBlank()) || semester != null;
        Join<Registration, Student> studentJoin =
                needsStudent ? registrationRoot.join("student") : null;

        if (departmentId != null) {
            Predicate offeredBy = cb.equal(subjectJoin.join("department", JoinType.LEFT).get("id"), departmentId);
            Predicate eligibleFor = cb.equal(subjectJoin.join("eligibleDepartments", JoinType.LEFT).get("id"), departmentId);
            predicates.add(cb.or(offeredBy, eligibleFor));
        }

        SubjectType subjectTypeFilter = SubjectType.fromNullable(subjectType);
        if (subjectTypeFilter != null) {
            predicates.add(cb.equal(subjectJoin.get("subjectType"), subjectTypeFilter));
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            Predicate namePredicate = cb.like(cb.lower(studentJoin.get("name")), "%" + searchQuery.toLowerCase() + "%");
            Predicate usnPredicate = cb.like(cb.lower(studentJoin.get("rollNo")), "%" + searchQuery.toLowerCase() + "%");
            predicates.add(cb.or(namePredicate, usnPredicate));
        }

        if (semester != null) {
            // same COALESCE as RegistrationSpecification — match the displayed semester, not the
            // raw snapshot column
            predicates.add(cb.equal(
                    cb.coalesce(registrationRoot.get("snapSemester"), studentJoin.get("currentSemester")),
                    semester));
        }

        if (studentRollNos != null && !studentRollNos.isEmpty()) {
            predicates.add(registrationRoot.get("student").get("rollNo").in(studentRollNos));
        }

        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(new Predicate[0])));
        }

        query.orderBy(cb.asc(subjectJoin.get("subjectName")));
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * Blank/absent means REGULAR (the field is optional). An UNRECOGNISED value is a 400 — it used
     * to fall back to REGULAR too, so "ELECTIV" silently created a REGULAR subject that then never
     * reached the students the elective was meant for. The old comment justified the fallback with
     * "the form only sends REGULAR or ELECTIVE", but the clone path builds these requests from
     * client-supplied rows, so that was a client-trust assumption on a write path.
     */
    private SubjectType resolveSubjectType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SubjectType.REGULAR;
        }
        SubjectType type = SubjectType.fromNullable(raw);
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown subject type '" + raw + "'. Use REGULAR or ELECTIVE.");
        }
        return type;
    }

    /**
     * Eligible departments by id, rejecting ids that don't exist. findAllById just omits unknown
     * ids, so a stale one silently saved the subject with narrower eligibility than the admin
     * chose. Same size check RegistrationService already applies to subject ids.
     */
    private List<Department> resolveEligibleDepartments(Collection<Long> ids) {
        List<Department> found = departmentRepository.findAllById(ids);
        if (found.size() != new java.util.HashSet<>(ids).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "One or more selected eligible departments no longer exist.");
        }
        return found;
    }
}
