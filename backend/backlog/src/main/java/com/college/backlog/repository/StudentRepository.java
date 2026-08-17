package com.college.backlog.repository;

import com.college.backlog.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Bulk progression lives here as native set-based SQL, NOT as entity loops. A whole-college run is
 * ~20k students; loading them as entities would send ~20k UPDATEs and ~20k INSERTs to Neon, one
 * network round trip each. As written, a run is 4 statements regardless of cohort size, and the
 * audit rows are built by INSERT ... SELECT so they never leave Postgres.
 *
 * <p>Three predicates recur below and must stay in step:
 * <ul>
 *   <li><b>selection</b> — the filters: optional even semester, optional 2-letter branch code.</li>
 *   <li><b>promotable</b> — in selection, not excluded, current in (2,4,6) (even and below the
 *       cap), entry odd 1..7. Anything failing the parity part is legacy data that gets REPORTED,
 *       never guessed at.</li>
 *   <li><b>the rest</b> — in selection, not promotable; classified by CASE into excluded / at-max /
 *       invalid.</li>
 * </ul>
 *
 * <p><b>:excluded must never be empty</b> — SQL {@code NOT IN ()} is a syntax error. Callers pass
 * {@code BulkProgressionService.NO_EXCLUSIONS} (a sentinel that cannot be a USN) instead.
 */
@Repository
public interface StudentRepository extends JpaRepository<Student, String>, JpaSpecificationExecutor<Student> {
    Optional<Student> findByRollNo(String rollNo);

    List<Student> findByRollNoInOrderByRollNo(Collection<String> rollNos);

    // Department-delete guard: students carry their branch as the 2-letter code with no FK, so a
    // department can't be removed while students of that branch exist — they couldn't register.
    boolean existsByBranchIgnoreCase(String branch);

    // ---- bulk progression ----

    // The CASTs are load-bearing, not decoration: Postgres cannot infer the type of a bare
    // parameter in "? IS NULL" and fails the statement with "could not determine data type of
    // parameter". Only reproduces against a real database — no unit test covers it.
    // lower(), not upper(): V5 created ix_students_branch_lower ON students (lower(branch)), and a
    // functional index only serves the exact expression it was built on. upper() seq-scans.
    String SELECTION = """
        (CAST(:filterSemester AS integer) IS NULL OR s.current_semester = CAST(:filterSemester AS integer))
        AND (CAST(:filterDeptCode AS text) IS NULL OR lower(s.branch) = lower(CAST(:filterDeptCode AS text)))
        """;

    String PROMOTABLE = SELECTION + """
        AND s.roll_no NOT IN (:excluded)
        AND s.current_semester < 8 AND MOD(s.current_semester, 2) = 0
        AND MOD(s.entry_semester, 2) = 1 AND s.entry_semester BETWEEN 1 AND 7
        """;

    /** Selection minus promotable. {@code SELECTION AND NOT (SELECTION AND ...)} is redundant but
     *  harmless; spelling it this way keeps ONE definition of "promotable" to negate. */
    String NOT_PROMOTABLE = SELECTION + "AND NOT (" + PROMOTABLE + ")\n";

    /** Why a selected student is not moving. Extracted because the preview and the audit MUST
     *  classify the same student identically — two copies could silently disagree. */
    String OUTCOME_CASE = """
        CASE
          WHEN s.roll_no IN (:excluded) THEN 'EXCLUDED_BY_ADMIN'
          WHEN MOD(s.current_semester, 2) <> 0
               OR s.current_semester NOT BETWEEN 1 AND 8
               OR MOD(s.entry_semester, 2) <> 1
               OR s.entry_semester NOT BETWEEN 1 AND 7
            THEN 'SKIPPED_INVALID_SEMESTER'
          ELSE 'SKIPPED_AT_MAX'
        END
        """;

    @Query(value = "SELECT count(*) FROM students s WHERE " + PROMOTABLE, nativeQuery = true)
    long countPromotable(@Param("filterSemester") Integer filterSemester,
                         @Param("filterDeptCode") String filterDeptCode,
                         @Param("excluded") Collection<String> excluded);

    /**
     * The not-promoted students a HUMAN must look at: the admin's own exclusions plus legacy rows
     * with an invalid semester. Both are small by nature.
     *
     * <p>Deliberately EXCLUDES {@code SKIPPED_AT_MAX}. Every semester-8 student in the selection is
     * "not promoted", so an unfiltered whole-college preview would otherwise return roughly a
     * quarter of the student body — thousands of rows nobody reads. Their count comes from
     * {@link #countAtMax} instead.
     *
     * <p>Columns: roll_no, current_semester, entry_semester, outcome.
     */
    @Query(value = "SELECT s.roll_no, s.current_semester, s.entry_semester, " + OUTCOME_CASE
                   + " AS outcome FROM students s WHERE " + NOT_PROMOTABLE
                   + " AND (s.roll_no IN (:excluded) OR s.current_semester <> 8"
                   + "      OR MOD(s.entry_semester, 2) <> 1) ORDER BY s.roll_no",
           nativeQuery = true)
    List<Object[]> findNotPromotableNeedingAttention(@Param("filterSemester") Integer filterSemester,
                                                     @Param("filterDeptCode") String filterDeptCode,
                                                     @Param("excluded") Collection<String> excluded);

    /** How many selected students are already at the cap — a count, never a list. */
    @Query(value = "SELECT count(*) FROM students s WHERE " + NOT_PROMOTABLE
                   + " AND s.roll_no NOT IN (:excluded) AND s.current_semester = 8"
                   + " AND MOD(s.entry_semester, 2) = 1",
           nativeQuery = true)
    long countAtMax(@Param("filterSemester") Integer filterSemester,
                    @Param("filterDeptCode") String filterDeptCode,
                    @Param("excluded") Collection<String> excluded);

    /**
     * Audit rows for the students about to move — MUST run BEFORE {@link #bulkPromote}, so
     * current_semester is still the pre-state captured as semester_from.
     *
     * @return rows written, i.e. the promoted count
     */
    @Modifying
    @Query(value = """
        INSERT INTO progression_batch_students
            (batch_id, roll_no, semester_from, semester_to, outcome)
        SELECT :batchId, s.roll_no, s.current_semester, s.current_semester + 2, 'PROMOTED'
        FROM students s
        WHERE """ + PROMOTABLE, nativeQuery = true)
    int insertPromotedAudit(@Param("batchId") Long batchId,
                            @Param("filterSemester") Integer filterSemester,
                            @Param("filterDeptCode") String filterDeptCode,
                            @Param("excluded") Collection<String> excluded);

    /**
     * Audit rows for everyone in the selection who is NOT moving — including the SKIPPED_AT_MAX
     * students the preview only counts, since the audit records the whole run. Classified by the
     * same {@link #OUTCOME_CASE} as the preview, so the two can never disagree about a student.
     * semester_to stays null — nothing moved (DB CHECK enforces it).
     *
     * @return rows written, i.e. excluded + skipped
     */
    @Modifying
    @Query(value = "INSERT INTO progression_batch_students"
                   + " (batch_id, roll_no, semester_from, semester_to, outcome)"
                   + " SELECT :batchId, s.roll_no, s.current_semester, NULL, " + OUTCOME_CASE
                   + " FROM students s WHERE " + NOT_PROMOTABLE,
           nativeQuery = true)
    int insertNotPromotedAudit(@Param("batchId") Long batchId,
                               @Param("filterSemester") Integer filterSemester,
                               @Param("filterDeptCode") String filterDeptCode,
                               @Param("excluded") Collection<String> excluded);

    /**
     * The promotion itself: one statement, whatever the cohort size. Runs LAST so the audit above
     * captured the pre-state. {@code clearAutomatically} because a bulk UPDATE bypasses the
     * persistence context, which would otherwise hold stale Student entities.
     *
     * @return rows updated — must equal the promoted count, or the transaction is rolled back
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE students s SET current_semester = s.current_semester + 2 WHERE "
                   + PROMOTABLE, nativeQuery = true)
    int bulkPromote(@Param("filterSemester") Integer filterSemester,
                    @Param("filterDeptCode") String filterDeptCode,
                    @Param("excluded") Collection<String> excluded);
}
