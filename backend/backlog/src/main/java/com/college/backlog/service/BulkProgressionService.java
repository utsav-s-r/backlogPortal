package com.college.backlog.service;

import com.college.backlog.controller.dto.BulkProgressionPreviewResponse;
import com.college.backlog.controller.dto.BulkProgressionRequest;
import com.college.backlog.model.ProgressionBatch;
import com.college.backlog.model.ProgressionOutcome;
import com.college.backlog.model.Student;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProgressionBatchRepository;
import com.college.backlog.repository.ProgressionBatchStudentRepository;
import com.college.backlog.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Institution-wide semester promotion: every student in the selection moves +2, except the ones the
 * admin holds back. ADMIN-only, enforced at the controller.
 *
 * <p><b>This writes {@code students.current_semester} and its own audit tables. It must NEVER touch
 * {@code student_semester_terms}.</b> The academic-year timeline has exactly two writers
 * ({@code backfillLinear} at creation, {@code overrideProgression} for a hand correction); a third
 * is what made progression disagreement possible in the deleted bulk tools. Detention corrections
 * stay on the per-student Semesters panel.
 *
 * <p>Four guards, all server-side:
 * <ol>
 *   <li>An active exam cycle blocks the run (409). A wrongly promoted student who then REGISTERS
 *       creates immutable history that can only be rejected, never deleted — this is the only
 *       unrecoverable failure mode, and closing registration removes it.</li>
 *   <li>An unknown or malformed USN in the exclusion list fails the whole request (400). Silently
 *       ignoring a typo is exactly how a detained student gets promoted.</li>
 *   <li>{@code expectedCount} must still match (409) — the double-click guard.</li>
 *   <li>Odd current / even entry semesters are reported, never guessed at.</li>
 * </ol>
 *
 * <p>Cost is 4 statements regardless of cohort size — see {@link StudentRepository}.
 */
@Service
public class BulkProgressionService {

    private static final Logger log = LoggerFactory.getLogger(BulkProgressionService.class);

    /** SQL {@code NOT IN ()} is a syntax error, so the exclusion list is never empty at the query
     *  boundary. No real USN can be "-" ({@code Usn} requires the 1MS&lt;YY&gt;&lt;BR&gt;&lt;NNN&gt; form). */
    static final String NO_EXCLUSIONS = "-";

    @Autowired private StudentRepository studentRepository;
    @Autowired private ProgressionBatchRepository batchRepository;
    @Autowired private ProgressionBatchStudentRepository batchStudentRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private StudentManagementService studentService;

    // ---- preview ----

    @Transactional(readOnly = true)
    public BulkProgressionPreviewResponse preview(BulkProgressionRequest req) {
        Selection sel = resolve(req);

        long promoteCount = studentRepository.countPromotable(sel.semester(), sel.deptCode(), sel.excluded());
        long atMaxCount = studentRepository.countAtMax(sel.semester(), sel.deptCode(), sel.excluded());
        List<BulkProgressionPreviewResponse.Row> notPromoted = new ArrayList<>();
        for (Object[] row : studentRepository.findNotPromotableNeedingAttention(
                sel.semester(), sel.deptCode(), sel.excluded())) {
            notPromoted.add(new BulkProgressionPreviewResponse.Row(
                (String) row[0],
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                (String) row[3]));
        }
        return new BulkProgressionPreviewResponse(promoteCount, atMaxCount, notPromoted);
    }

    /** The validated run parameters. Resolved identically for preview and commit so the two can't
     *  drift — a preview that validated differently from the run would be worthless. */
    private record Selection(Integer semester, String deptCode, Set<String> excluded) {}

    private Selection resolve(BulkProgressionRequest req) {
        assertRegistrationClosed();
        return new Selection(validatedSemesterFilter(req.getSemester()),
                             StudentManagementService.trimToNull(req.getDeptCode()),
                             validatedExclusions(req.getExcludeRollNos()));
    }

    // ---- commit ----

    /**
     * One transaction: header insert, audit of who moves (capturing the PRE-state), audit of who
     * doesn't, then the promotion. Order matters — the UPDATE must come last or semester_from would
     * record the post-state.
     */
    @Transactional
    public ProgressionBatch run(BulkProgressionRequest req, String actor) {
        Selection sel = resolve(req);
        Integer semester = sel.semester();
        String deptCode = sel.deptCode();
        Set<String> excluded = sel.excluded();

        if (req.getExpectedCount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Confirm the run from a preview — expectedCount is required.");
        }
        long actual = studentRepository.countPromotable(semester, deptCode, excluded);
        if (actual != req.getExpectedCount()) {
            // also the double-run guard: a second click carries a count the DB no longer agrees with
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The student list changed since your preview (" + req.getExpectedCount()
                    + " expected, " + actual + " now). Preview again before running.");
        }

        ProgressionBatch batch = batchRepository.save(new ProgressionBatch(actor, semester, deptCode));

        int promoted = studentRepository.insertPromotedAudit(batch.getId(), semester, deptCode, excluded);
        int notPromoted = studentRepository.insertNotPromotedAudit(batch.getId(), semester, deptCode, excluded);
        int updated = studentRepository.bulkPromote(semester, deptCode, excluded);

        if (updated != promoted) {
            // the audit and the promotion disagree — the audit would be a lie, so commit neither
            throw new IllegalStateException(
                "Bulk progression aborted: audited " + promoted + " but updated " + updated + ".");
        }

        // counted from the rows actually written, not from the request: an excluded USN outside
        // this run's filter never becomes a row, so the request list would over-count it
        int excludedCount = batchStudentRepository.countByBatchIdAndOutcome(
            batch.getId(), ProgressionOutcome.EXCLUDED_BY_ADMIN);
        batch.setPromotedCount(promoted);
        batch.setExcludedCount(excludedCount);
        batch.setSkippedCount(notPromoted - excludedCount);
        batchRepository.save(batch);

        log.info("PROGRESSION_BULK actor={} batchId={} semester={} dept={} promoted={} notPromoted={}",
                actor, batch.getId(), semester, deptCode, promoted, notPromoted);
        return batch;
    }

    // ---- guards ----

    /** Registration must be closed: a wrong promotion is reversible, a registration made under it
     *  is not (registrations are immutable history). Mirrors RegistrationService's active-cycle
     *  check, which is the single source of truth for "open". */
    private void assertRegistrationClosed() {
        if (examCycleRepository.findByActiveTrue().isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Close the active exam cycle before promoting students. Students could otherwise "
                    + "register under a wrong semester, and registrations cannot be deleted.");
        }
    }

    /** Null = all semesters. Otherwise it must be a semester a student can actually sit in.
     *  Semesters owns the wording — a second copy here would drift from the create/update path. */
    private Integer validatedSemesterFilter(Integer semester) {
        if (semester == null) return null;
        try {
            Semesters.assertCurrentSemester(semester);
        } catch (IllegalArgumentException e) {
            // wrapped at the call site, per the house rule against mapping IAE centrally
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return semester;
    }

    /**
     * Normalise, de-duplicate and verify every excluded USN exists. Fails the WHOLE request on the
     * first bad entry rather than dropping it — a typo'd exclusion silently promotes a student who
     * was meant to be held back, which is the failure this list exists to prevent.
     *
     * @return the normalised set, never empty (see {@link #NO_EXCLUSIONS})
     */
    private Set<String> validatedExclusions(List<String> raw) {
        Set<String> normalised = new LinkedHashSet<>();
        if (raw != null) {
            for (String entry : raw) {
                String usn = StudentManagementService.trimToNull(entry);
                if (usn == null) continue; // blank lines from a pasted list
                normalised.add(studentService.normalizeUsn(usn));
            }
        }
        if (!normalised.isEmpty()) {
            // ONE query, not one per USN: a college-wide detention list runs to hundreds of
            // entries, and a probe each would be hundreds of round trips to a remote Neon —
            // dwarfing the four set-based statements this whole design exists to achieve.
            Set<String> found = studentRepository.findByRollNoInOrderByRollNo(normalised).stream()
                .map(Student::getRollNo)
                .collect(Collectors.toSet());
            List<String> missing = normalised.stream().filter(u -> !found.contains(u)).toList();
            if (!missing.isEmpty()) {
                // name them ALL — fixing a pasted list one 400 at a time is miserable
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not students: " + String.join(", ", missing)
                        + ". Fix the exclusion list and preview again.");
            }
        }
        if (normalised.isEmpty()) {
            normalised.add(NO_EXCLUSIONS);
        }
        return normalised;
    }
}
