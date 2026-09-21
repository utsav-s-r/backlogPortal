package com.college.backlog.service;

import com.college.backlog.model.Registration;
import com.college.backlog.model.Student;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.Locale;

/**
 * The admin registration list's free-text search, in ONE place. It was written twice — in
 * {@link RegistrationSpecification} and in {@code SubjectService}'s subject-dropdown query, whose
 * comment already said the two must agree — so a fix to one silently left the other offering
 * subjects the list does not show.
 *
 * <p>Matches three fields: the SNAPSHOT name the table actually displays, the student's current
 * name, and the roll number. Both names on purpose — the snapshot alone would stop finding a
 * renamed student by the name their office knows them by, and the live name alone (the original
 * behaviour) does not match the name on screen.
 */
public final class RegistrationSearch {

    /** Postgres' default for LIKE, and what Hibernate emits when given it explicitly. */
    private static final char ESCAPE = '\\';

    private RegistrationSearch() {}

    /**
     * {@code %term%}, with the term's own wildcards neutered. Unescaped, "50%" matched everything
     * beginning with 50 and "A_B" matched "AxB" — wrong results rather than an injection, since
     * the pattern is still a bound parameter. The backslash goes first, or it would escape the
     * escapes added after it.
     */
    static String contains(String rawQuery) {
        String escaped = rawQuery.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * @param studentJoin the join both callers already make for their other predicates — passing
     *     it in rather than joining here keeps this from adding a second join to the same table.
     */
    public static Predicate studentMatches(CriteriaBuilder cb, Root<Registration> registration,
                                           Join<Registration, Student> studentJoin, String rawQuery) {
        String pattern = contains(rawQuery);
        return cb.or(
                cb.like(cb.lower(registration.get("snapName")), pattern, ESCAPE),
                cb.like(cb.lower(studentJoin.get("name")), pattern, ESCAPE),
                cb.like(cb.lower(studentJoin.get("rollNo")), pattern, ESCAPE));
    }
}
