package com.college.backlog.service;

import com.college.backlog.model.Student;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filters the student roster; any argument may be null (= no filter).
 * {@code rollNoLike} is a USN SQL-LIKE pattern the controller builds from the branch-code and
 * admission-year filters — the USN is the single source of both, so dept-scoping rides on it.
 * {@code semester} matches currentSemester, {@code query} is a free-text contains on USN or name,
 * and {@code rollNoIn} restricts to an explicit USN set (the proctor scope — callers must handle
 * the empty set, since an empty IN list is not valid SQL).
 */
public class StudentSpecification implements Specification<Student> {

    private final String rollNoLike;
    private final Integer semester;
    private final String query;
    private final Collection<String> rollNoIn;

    public StudentSpecification(String rollNoLike, Integer semester, String query) {
        this(rollNoLike, semester, query, null);
    }

    public StudentSpecification(String rollNoLike, Integer semester, String query,
                                Collection<String> rollNoIn) {
        this.rollNoLike = rollNoLike;
        this.semester = semester;
        this.query = query;
        this.rollNoIn = rollNoIn;
    }

    @Override
    public Predicate toPredicate(Root<Student> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        if (rollNoLike != null && !rollNoLike.isBlank()) {
            predicates.add(cb.like(root.get("rollNo"), rollNoLike));
        }
        if (semester != null) {
            predicates.add(cb.equal(root.get("currentSemester"), semester));
        }
        if (rollNoIn != null && !rollNoIn.isEmpty()) {
            predicates.add(root.get("rollNo").in(rollNoIn));
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim().toLowerCase() + "%";
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("rollNo")), like),
                cb.like(cb.lower(root.get("name")), like)));
        }
        return cb.and(predicates.toArray(new Predicate[0]));
    }
}
