package com.college.backlog.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question three catch blocks used to skip: WHICH constraint rejected the row. Getting this
 * wrong meant telling a student with zero pending registrations to wait for one, and telling an
 * admin a subject already existed when it didn't.
 */
class ConstraintsTest {

    /** Shape Spring produces: the constraint name is down in the cause chain, not the top message. */
    private DataIntegrityViolationException violation(String causeMessage) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("org.hibernate.exception.ConstraintViolationException",
                        new RuntimeException(causeMessage)));
    }

    @Test
    void findsTheConstraintNameAnywhereInTheCauseChain() {
        DataIntegrityViolationException e = violation(
                "ERROR: duplicate key value violates unique constraint \"uq_subjects_code_year\"");

        assertThat(Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)).isTrue();
    }

    @Test
    void doesNotMatchADifferentConstraint() {
        DataIntegrityViolationException e = violation(
                "ERROR: duplicate key value violates unique constraint \"uq_pending_reg_per_cycle\"");

        assertThat(Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)).isFalse();
        assertThat(Constraints.isViolationOf(e, Constraints.PENDING_REGISTRATION_PER_CYCLE)).isTrue();
    }

    /** The case that caused the false "already exists" / "you already have a pending registration". */
    @Test
    void anUnrelatedViolationMatchesNothing() {
        DataIntegrityViolationException e = violation(
                "ERROR: null value in column \"semester\" violates not-null constraint");

        assertThat(Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)).isFalse();
        assertThat(Constraints.isViolationOf(e, Constraints.PENDING_REGISTRATION_PER_CYCLE)).isFalse();
    }

    @Test
    void toleratesANullMessageAndACauselessException() {
        assertThat(Constraints.isViolationOf(new DataIntegrityViolationException(null),
                Constraints.SUBJECT_CODE_YEAR)).isFalse();
    }

    /** students_pkey is a substring of proctor_students_pkey: a contains() match would report a
     *  lost proctor claim as a duplicate student, and the reverse. */
    @Test
    void matchesTheWholeNameNotASubstringOfALongerOne() {
        DataIntegrityViolationException claim = violation(
                "ERROR: duplicate key value violates unique constraint \"proctor_students_pkey\"");
        DataIntegrityViolationException student = violation(
                "ERROR: duplicate key value violates unique constraint \"students_pkey\"");

        assertThat(Constraints.isViolationOf(claim, Constraints.PROCTOR_ASSIGNMENT_ROLL_NO)).isTrue();
        assertThat(Constraints.isViolationOf(claim, Constraints.STUDENT_ROLL_NO)).isFalse();
        assertThat(Constraints.isViolationOf(student, Constraints.STUDENT_ROLL_NO)).isTrue();
        assertThat(Constraints.isViolationOf(student, Constraints.PROCTOR_ASSIGNMENT_ROLL_NO)).isFalse();
    }

    /** Spring and Hibernate also print the name bare or bracketed, e.g. "constraint [students_pkey]". */
    @Test
    void matchesTheNameAtTheEdgesOfTheMessageAndInsideBrackets() {
        assertThat(Constraints.isViolationOf(violation("students_pkey"), Constraints.STUDENT_ROLL_NO)).isTrue();
        assertThat(Constraints.isViolationOf(violation("constraint [students_pkey]"),
                Constraints.STUDENT_ROLL_NO)).isTrue();
    }

    @Test
    void matchIsCaseInsensitive() {
        DataIntegrityViolationException e = violation("violates unique constraint \"UQ_SUBJECTS_CODE_YEAR\"");

        assertThat(Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)).isTrue();
    }
}
