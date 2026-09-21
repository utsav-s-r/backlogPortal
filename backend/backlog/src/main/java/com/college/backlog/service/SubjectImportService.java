package com.college.backlog.service;

import com.college.backlog.controller.dto.BatchResult;
import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.controller.dto.SubjectImportRequest;
import com.college.backlog.controller.dto.SubjectRowResult;
import com.college.backlog.model.Department;
import com.college.backlog.model.SubjectType;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.SubjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bulk subject creation from a pasted CSV, the first-load counterpart to {@link SubjectCloneService}
 * (which only helps once a previous year's catalog already exists).
 *
 * <p>Year and department are stamped from the REQUEST, never read from a row — the same rule clone
 * applies to its target year, and {@link SubjectImportRequest.Row} carries no field for either.
 * {@code dryRun} reports WOULD_CREATE / WOULD_SKIP without writing.
 *
 * <p>The two lookups a row needs — "does this code already exist this year?" and "what id is this
 * department code?" — are resolved ONCE for the whole file, not per row. Neon is remote, so round
 * trips dominate: per-row lookups made a 100-row elective import ~200 queries against a table with
 * a dozen rows in it, and made a dry run (which writes nothing) O(N) queries instead of 2.
 */
@Service
public class SubjectImportService {

    private static final Logger log = LoggerFactory.getLogger(SubjectImportService.class);

    @Autowired private SubjectRepository subjectRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SubjectService subjectService;

    // NOT @Transactional: each createSubject gets its own transaction, so one bad row can't roll
    // back the rest of the batch.
    public BatchResult<SubjectRowResult> importRows(Long deptId, int academicYear, boolean dryRun,
                                                    List<SubjectImportRequest.Row> rows) {
        List<SubjectImportRequest.Row> safeRows = rows == null ? List.of() : rows;
        int created = 0, skipped = 0, errors = 0;
        List<SubjectRowResult> results = new ArrayList<>();

        Set<String> alreadyPresent = existingCodesFor(academicYear, safeRows);
        Map<String, Long> deptIdsByCode = departmentIdsByLowercaseCode();

        for (SubjectImportRequest.Row row : safeRows) {
            String code = row.getCourseCode() == null ? "" : row.getCourseCode().trim();
            Integer semester = row.getSemester();
            try {
                SubjectType type = validateRow(row, code);

                if (alreadyPresent.contains(code)) {
                    results.add(new SubjectRowResult(code, semester,
                        dryRun ? "WOULD_SKIP" : "SKIPPED_EXISTS", "Already exists for this year"));
                    skipped++;
                    continue;
                }

                // Resolved even on a dry run: an unknown code is the likeliest mistake in the file,
                // and a preview that stayed silent would promise a WOULD_CREATE the real run turns
                // into an ERROR — the preview/apply parity break already fixed on the progression
                // import and on clone.
                List<Long> eligibleIds = resolveEligibleDeptIds(row.getEligibleDeptCodes(), deptIdsByCode);
                // The single-create path's own rule and its exact sentence, rather than a second
                // wording for one institutional rule.
                SubjectService.assertElectiveNamesItsDepartments(type, eligibleIds);

                if (dryRun) {
                    results.add(new SubjectRowResult(code, semester, "WOULD_CREATE", null));
                    created++;
                    continue;
                }

                SubjectCreateRequest req = new SubjectCreateRequest();
                req.setSubjectName(row.getSubjectName());
                req.setCourseCode(code);
                req.setSemester(semester);
                req.setCredits(row.getCredits());
                req.setAcademicYearOffered(academicYear); // forced server-side, never from the row
                req.setDeptId(deptId);                    // ditto — the caller's scope decides this
                req.setSubjectType(row.getSubjectType());
                req.setEligibleDeptIds(eligibleIds);
                subjectService.createSubject(req);

                results.add(new SubjectRowResult(code, semester, "CREATED", null));
                created++;
            } catch (DataIntegrityViolationException e) {
                // Only the code+year unique index means "already exists" — reporting any other
                // violation that way tells the admin their catalog is complete when it isn't,
                // which stays invisible until someone diffs two years. Still reachable despite the
                // batched pre-check: a code duplicated WITHIN one file passes that check twice.
                if (Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)) {
                    results.add(new SubjectRowResult(code, semester, "SKIPPED_EXISTS", "Already exists"));
                    skipped++;
                } else {
                    log.error("SUBJECT_IMPORT_ROW_FAILED code={} semester={}", code, semester, e);
                    results.add(new SubjectRowResult(code, semester, "ERROR",
                        "Could not create this subject."));
                    errors++;
                }
            } catch (ResponseStatusException e) {
                // createSubject's year-range / elective / unknown-type refusals. Must stay ABOVE the
                // generic catch: RSE is itself a RuntimeException, and that clause would flatten
                // this actionable reason into the generic sentence.
                results.add(new SubjectRowResult(code, semester, "ERROR", e.getReason()));
                errors++;
            } catch (NumberFormatException e) {
                // NFE extends IllegalArgumentException, so without this clause it lands below and
                // its raw message is shown as if the ROW were malformed — a server bug dressed as a
                // data problem. Must precede the IAE clause; the reverse does not compile.
                log.error("SUBJECT_IMPORT_ROW_FAILED code={} semester={}", code, semester, e);
                results.add(new SubjectRowResult(code, semester, "ERROR",
                    "Could not create this subject."));
                errors++;
            } catch (IllegalArgumentException e) {
                // Only validateRow and the dept-code resolution throw IAE here, with curated
                // literal messages, so surfacing getMessage() is safe.
                results.add(new SubjectRowResult(code, semester, "ERROR", e.getMessage()));
                errors++;
            } catch (RuntimeException e) {
                // The batch is deliberately NOT transactional, so every row before this one is
                // ALREADY COMMITTED. Letting an unexpected failure escape would abort the loop and
                // surface as a request-level 500, destroying the record of what actually happened
                // while leaving those rows in the database. Not hypothetical here: a Neon
                // connection drop is a DataAccessResourceFailureException and a commit-time failure
                // a TransactionSystemException — neither extends DataIntegrityViolationException,
                // so all three clauses above miss them. Logged: a result row can't carry a trace.
                log.error("SUBJECT_IMPORT_ROW_FAILED code={} semester={}", code, semester, e);
                results.add(new SubjectRowResult(code, semester, "ERROR",
                    "Could not create this subject."));
                errors++;
            }
        }
        return new BatchResult<>(dryRun, created, skipped, errors, results);
    }

    /** Codes from this file that already exist in the target year — one query, not one per row. */
    private Set<String> existingCodesFor(int academicYear, List<SubjectImportRequest.Row> rows) {
        Set<String> codes = rows.stream()
            .map(r -> r.getCourseCode() == null ? "" : r.getCourseCode().trim())
            .filter(c -> !c.isEmpty())
            .collect(Collectors.toCollection(HashSet::new));
        // an empty IN list is not valid SQL
        if (codes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(subjectRepository.findExistingCourseCodes(academicYear, codes));
    }

    /** Every department once, keyed by lowercase code — the table holds a handful of rows. */
    private Map<String, Long> departmentIdsByLowercaseCode() {
        Map<String, Long> byCode = new HashMap<>();
        for (Department d : departmentRepository.findAll()) {
            if (d.getCode() != null) {
                byCode.put(d.getCode().trim().toLowerCase(Locale.ROOT), d.getId());
            }
        }
        return byCode;
    }

    /** Row-shape checks, before anything touches the database. Returns the resolved type. */
    private SubjectType validateRow(SubjectImportRequest.Row row, String code) {
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Course code is required.");
        }
        if (row.getSubjectName() == null || row.getSubjectName().isBlank()) {
            throw new IllegalArgumentException("Subject name is required.");
        }
        if (row.getSemester() == null) {
            throw new IllegalArgumentException("Semester is required.");
        }
        // the shared 1..8 rule, not a re-typed literal — a wider range anywhere silently locks
        // students out of registering (EligibilityService returns an empty window above 8)
        Semesters.assertStudiable(row.getSemester());
        if (row.getCredits() == null) {
            throw new IllegalArgumentException("Credits are required.");
        }
        if (row.getCredits() < 0) {
            throw new IllegalArgumentException("Credits cannot be negative.");
        }
        // The single-create path's own parser, so the legal vocabulary has ONE definition: an
        // unknown type is refused rather than defaulted, since "ELECTIV" silently becoming REGULAR
        // creates a subject the elective's students never see. It throws a 400
        // ResponseStatusException, which this loop reports as an ERROR row for just this line.
        return SubjectService.resolveSubjectType(row.getSubjectType());
    }

    /**
     * Department codes to ids, against the pre-loaded map. An unknown code is an ERROR for the row,
     * never dropped: dropping it would save the elective with narrower eligibility than the file
     * asked for, and an elective nobody is eligible for is registrable by nobody while still
     * listing in the catalog.
     */
    private List<Long> resolveEligibleDeptIds(List<String> codes, Map<String, Long> deptIdsByCode) {
        List<Long> ids = new ArrayList<>();
        if (codes == null) {
            return ids;
        }
        for (String code : codes) {
            Long id = deptIdsByCode.get(code.trim().toLowerCase(Locale.ROOT));
            if (id == null) {
                throw new IllegalArgumentException("Unknown department code '" + code + "'.");
            }
            ids.add(id);
        }
        return ids;
    }
}
