package com.college.backlog.service;

import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filter set for the admin registration list, counts and PDF export — one class so all three
 * always agree on what "the filtered set" means.
 *
 * Built through {@link #builder()} rather than constructors: the fields are three Longs, two
 * Strings and two collections, so positional args were a silent swap hazard (a swapped pair
 * compiles, throws nothing, and returns an empty list that reads as "no registrations").
 */
public class RegistrationSpecification implements Specification<Registration> {

    private final Long subjectId;
    // Dept scope AND the admin's dept filter share these — the predicate is identical either way.
    // Which values land here is the CALLER's decision, and it is security-critical: a dept-pinned
    // role's own department must always win over a request parameter.
    //
    // A department is INVOLVED in a registration three ways: it offers one of the subjects, one of
    // its subjects lists it as eligible, or the STUDENT is one of theirs. The third arm needs the
    // department's CODE, because students carry their branch as a code with no FK — hence a pair,
    // set together through department(id, code) so a caller cannot configure one without the
    // other and silently narrow the scope back to subjects.
    private final Long departmentId;
    private final String departmentCode;
    private final String subjectType;
    private final String searchQuery;
    private final Integer semester;
    private final Long examCycleId;
    private final RegistrationStatus status;
    // proctor scope: only these students' registrations. null = no restriction; callers must
    // handle the empty set, since an empty IN list is not valid SQL.
    private final Collection<String> studentRollNos;
    // explicit row selection (export). Same empty-set rule as above.
    private final Collection<String> regIds;

    private RegistrationSpecification(Builder b) {
        this.subjectId = b.subjectId;
        this.departmentId = b.departmentId;
        this.departmentCode = b.departmentCode;
        this.subjectType = b.subjectType;
        this.searchQuery = b.searchQuery;
        this.semester = b.semester;
        this.examCycleId = b.examCycleId;
        this.status = b.status;
        this.studentRollNos = b.studentRollNos;
        this.regIds = b.regIds;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long subjectId;
        private Long departmentId;
        private String departmentCode;
        private String subjectType;
        private String searchQuery;
        private Integer semester;
        private Long examCycleId;
        private RegistrationStatus status;
        private Collection<String> studentRollNos;
        private Collection<String> regIds;

        public Builder subjectId(Long v) { this.subjectId = v; return this; }
        /** The department involved: its id (for the subject arms) and its code (for the student
         *  arm). Both or neither — a null code silently drops the student's own department from
         *  its own scope. */
        public Builder department(Long id, String code) {
            this.departmentId = id;
            this.departmentCode = code;
            return this;
        }
        public Builder subjectType(String v) { this.subjectType = v; return this; }
        public Builder searchQuery(String v) { this.searchQuery = v; return this; }
        public Builder semester(Integer v) { this.semester = v; return this; }
        public Builder examCycleId(Long v) { this.examCycleId = v; return this; }
        public Builder status(RegistrationStatus v) { this.status = v; return this; }
        public Builder studentRollNos(Collection<String> v) { this.studentRollNos = v; return this; }
        public Builder regIds(Collection<String> v) { this.regIds = v; return this; }

        public RegistrationSpecification build() { return new RegistrationSpecification(this); }
    }

    @Override
    public Predicate toPredicate(Root<Registration> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        // searchQuery and semester both need `student`; joining per-predicate would emit two joins
        boolean needsStudent = (searchQuery != null && !searchQuery.isBlank()) || semester != null
                || (departmentCode != null && !departmentCode.isBlank());
        Join<Registration, Student> studentJoin =
                needsStudent ? root.join("student", JoinType.LEFT) : null;

        if (subjectId != null || departmentId != null || (subjectType != null && !subjectType.isBlank())) {
            // DISTINCT only when the subjects join below can fan out rows: the other joins are
            // many-to-one (student, examCycle) and can't duplicate. An unconditional DISTINCT
            // forced a sort/hash over the wide snapshot rows on every page and count.
            query.distinct(true);
            Join<Registration, Subject> subjectJoin = root.join("subjects", JoinType.LEFT);
            if (subjectId != null) {
                predicates.add(cb.equal(subjectJoin.get("id"), subjectId));
            }
            if (departmentId != null) {
                // Every department INVOLVED sees the registration, not only the ones whose
                // subjects are on it: a student registering solely for other departments'
                // electives was invisible to their OWN department, which is the department that
                // signs the form. Verification stays narrower — RegistrationController's
                // checkDeptAccess allows only the student's department.
                Predicate offeredBy = cb.equal(
                        subjectJoin.join("department", JoinType.LEFT).get("id"), departmentId);
                Predicate eligibleFor = cb.equal(
                        subjectJoin.join("eligibleDepartments", JoinType.LEFT).get("id"), departmentId);
                if (departmentCode != null && !departmentCode.isBlank()) {
                    // lower(), matching ix_students_branch_lower — a functional index serves only
                    // the exact expression it was built on.
                    Predicate ownStudent = cb.equal(
                            cb.lower(studentJoin.get("branch")), departmentCode.toLowerCase(java.util.Locale.ROOT));
                    predicates.add(cb.or(offeredBy, eligibleFor, ownStudent));
                } else {
                    predicates.add(cb.or(offeredBy, eligibleFor));
                }
            }
            SubjectType subjectTypeFilter = SubjectType.fromNullable(subjectType);
            if (subjectTypeFilter != null) {
                predicates.add(cb.equal(subjectJoin.get("subjectType"), subjectTypeFilter));
            }
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            // Shared with SubjectService's dropdown query, which must match the same rows.
            predicates.add(RegistrationSearch.studentMatches(cb, root, studentJoin, searchQuery));
        }

        if (semester != null) {
            // Matches the snapshot semester the list and the PDFs display. snap_semester is NOT
            // NULL, so the currentSemester fallback never applies.
            Expression<Integer> effectiveSemester =
                    cb.coalesce(root.get("snapSemester"), studentJoin.get("currentSemester"));
            predicates.add(cb.equal(effectiveSemester, semester));
        }

        if (examCycleId != null) {
            predicates.add(cb.equal(root.join("examCycle", JoinType.LEFT).get("id"), examCycleId));
        }

        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }

        if (studentRollNos != null && !studentRollNos.isEmpty()) {
            predicates.add(root.get("student").get("rollNo").in(studentRollNos));
        }

        if (regIds != null && !regIds.isEmpty()) {
            predicates.add(root.get("regId").in(regIds));
        }

        return cb.and(predicates.toArray(new Predicate[0]));
    }
}
