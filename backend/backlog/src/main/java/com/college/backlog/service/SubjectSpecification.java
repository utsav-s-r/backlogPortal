package com.college.backlog.service;

import com.college.backlog.model.Subject;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Filters the Manage Subjects list by department, academic year and/or semester; any may be
 *  null (= no filter). */
public class SubjectSpecification implements Specification<Subject> {

    private final Long deptId;
    private final Integer academicYearOffered;
    private final Integer semester;

    public SubjectSpecification(Long deptId, Integer academicYearOffered, Integer semester) {
        this.deptId = deptId;
        this.academicYearOffered = academicYearOffered;
        this.semester = semester;
    }

    @Override
    public Predicate toPredicate(Root<Subject> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        if (deptId != null) {
            predicates.add(cb.equal(root.get("department").get("id"), deptId));
        }
        if (academicYearOffered != null) {
            predicates.add(cb.equal(root.get("academicYearOffered"), academicYearOffered));
        }
        if (semester != null) {
            predicates.add(cb.equal(root.get("semester"), semester));
        }
        return cb.and(predicates.toArray(new Predicate[0]));
    }
}
