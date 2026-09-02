package com.college.backlog.service;

import com.college.backlog.controller.dto.SubjectCloneApplyRequest;
import com.college.backlog.controller.dto.SubjectCloneResult;
import com.college.backlog.controller.dto.SubjectRowResult;
import com.college.backlog.controller.dto.SubjectClonePreviewResponse;
import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.Subject;
import com.college.backlog.repository.SubjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Clones a department's subject offerings year to year: each row is copied verbatim with only
 * academic_year_offered moved to the target. {@code preview} builds the editable
 * draft; {@code apply} commits the approved rows, skipping existing ones with the
 * (course_code, academic_year_offered) uniqueness as backstop. See docs/adr/backlog-progression.md.
 */
@Service
public class SubjectCloneService {

    private static final Logger log = LoggerFactory.getLogger(SubjectCloneService.class);

    private static final List<Integer> ALL_SEMESTERS = List.of(1, 2, 3, 4, 5, 6, 7, 8);

    @Autowired private SubjectRepository subjectRepository;
    @Autowired private SubjectService subjectService;

    public SubjectClonePreviewResponse preview(Long deptId, int sourceYear, int targetYear, List<Integer> semesters) {
        List<Integer> sems = (semesters == null || semesters.isEmpty()) ? ALL_SEMESTERS : semesters;
        List<Subject> sources = subjectRepository
            .findByDepartment_IdAndAcademicYearOfferedAndSemesterInOrderBySemesterAscSubjectNameAsc(
                deptId, sourceYear, sems);

        List<SubjectClonePreviewResponse.Row> rows = new ArrayList<>();
        for (Subject s : sources) {
            // The code carries forward verbatim — a course keeps its identity across years, and
            // UNIQUE(course_code, academic_year_offered) is what separates the two offerings.
            String newCode = s.getCourseCode() == null ? "" : s.getCourseCode().trim();
            boolean blank = newCode.isEmpty();
            boolean exists = !blank
                && subjectRepository.existsByCourseCodeAndAcademicYearOffered(newCode, targetYear);
            List<Long> eligible = s.getEligibleDepartments() == null ? List.of()
                : s.getEligibleDepartments().stream().map(Department::getId).collect(Collectors.toList());
            // apply refuses a blank code, so preview must say ERROR here too rather than promising
            // a WOULD_CREATE that cannot happen — the preview/apply parity break fixed on the
            // progression import
            String status = blank ? "ERROR" : exists ? "WOULD_SKIP" : "WOULD_CREATE";
            String message = blank
                ? "This subject has no course code to copy."
                : exists ? "Already exists for the target year" : null;
            rows.add(new SubjectClonePreviewResponse.Row(
                s.getSubjectName(), newCode, s.getSemester(), s.getCredits(),
                s.getSubjectType() != null ? s.getSubjectType().name() : "REGULAR",
                eligible, status, message));
        }
        return new SubjectClonePreviewResponse(sourceYear, targetYear, deptId, rows);
    }

    // NOT @Transactional: each createSubject gets its own transaction, so a duplicate or bad row
    // can't roll back the rest of the batch.
    public SubjectCloneResult apply(Long deptId, int targetYear, List<SubjectCloneApplyRequest.Row> rows) {
        int created = 0, skipped = 0, errors = 0;
        List<SubjectRowResult> results = new ArrayList<>();
        if (rows != null) {
            for (SubjectCloneApplyRequest.Row row : rows) {
                String code = row.getCourseCode() == null ? "" : row.getCourseCode().trim();
                try {
                    if (code.isEmpty()) {
                        throw new IllegalArgumentException("Course code is required.");
                    }
                    // the shared 1..8 rule, not a re-typed literal — Semesters owns the range
                    Semesters.assertStudiable(row.getSemester());
                    if (subjectRepository.existsByCourseCodeAndAcademicYearOffered(code, targetYear)) {
                        results.add(new SubjectRowResult(code, row.getSemester(), "SKIPPED_EXISTS", null));
                        skipped++;
                        continue;
                    }
                    SubjectCreateRequest req = new SubjectCreateRequest();
                    req.setSubjectName(row.getSubjectName());
                    req.setCourseCode(code);
                    req.setSemester(row.getSemester());
                    req.setCredits(row.getCredits());
                    req.setAcademicYearOffered(targetYear); // forced server-side, never from the row
                    req.setDeptId(deptId);
                    req.setSubjectType(row.getSubjectType());
                    req.setEligibleDeptIds(row.getEligibleDeptIds() != null
                        ? row.getEligibleDeptIds() : new ArrayList<>());
                    subjectService.createSubject(req);
                    results.add(new SubjectRowResult(code, row.getSemester(), "CREATED", null));
                    created++;
                } catch (DataIntegrityViolationException e) {
                    // Only the code+year unique index means "already exists". Reporting every
                    // violation that way told the admin the row was already in the target year when
                    // it wasn't — and since these rows reach createSubject programmatically, @Valid
                    // never screens them, so other violations are reachable. A wrong SKIPPED_EXISTS
                    // reads as "catalog complete", which is invisible until someone diffs two years.
                    if (Constraints.isViolationOf(e, Constraints.SUBJECT_CODE_YEAR)) {
                        results.add(new SubjectRowResult(code, row.getSemester(), "SKIPPED_EXISTS", "Already exists"));
                        skipped++;
                    } else {
                        // the only record this row failed at all — the result row can't carry a trace
                        log.error("CLONE_ROW_FAILED code={} semester={}", code, row.getSemester(), e);
                        results.add(new SubjectRowResult(code, row.getSemester(), "ERROR",
                            "Could not create this subject."));
                        errors++;
                    }
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    // e.g. createSubject's year-range or elective-departments validation. Must stay
                    // ABOVE the generic catch: RSE is itself a RuntimeException, and that one would
                    // flatten this actionable reason into the generic sentence.
                    results.add(new SubjectRowResult(code, row.getSemester(), "ERROR", e.getReason()));
                    errors++;
                } catch (NumberFormatException e) {
                    // NFE extends IllegalArgumentException, so without this clause it would be
                    // caught below and its raw message ("For input string: \"null\"") shown to the
                    // admin as if the ROW were malformed — a server bug dressed as a data problem,
                    // and counted as a normal errors row instead of logged. Same reason
                    // GlobalExceptionHandler refuses to map IAE centrally. Must precede the IAE
                    // clause; the reverse order does not compile.
                    log.error("CLONE_ROW_FAILED code={} semester={}", code, row.getSemester(), e);
                    results.add(new SubjectRowResult(code, row.getSemester(), "ERROR",
                        "Could not create this subject."));
                    errors++;
                } catch (IllegalArgumentException e) {
                    // Only this method's OWN validation above throws IAE, with curated literal
                    // messages, so surfacing getMessage() is safe here.
                    results.add(new SubjectRowResult(code, row.getSemester(), "ERROR", e.getMessage()));
                    errors++;
                } catch (RuntimeException e) {
                    // The batch is deliberately NOT transactional (see above), so every row before
                    // this one is ALREADY COMMITTED. Letting an unexpected failure escape aborted
                    // the loop and surfaced as a request-level 500: CloneSubjectsTab never calls
                    // setResult, so the admin saw "Apply failed." with no result table and no way
                    // to know that some subjects now exist in the target year. Re-running is
                    // survivable (SKIPPED_EXISTS), but the record of what actually happened was
                    // destroyed. Both sibling batch paths already had this clause —
                    // StudentManagementController.importStudents and
                    // ProctorAssignmentController.assign — and clone was the one that was missed.
                    //
                    // Not hypothetical on this deployment: a Neon connection drop mid-batch is a
                    // DataAccessResourceFailureException and a commit-time failure is a
                    // TransactionSystemException; both extend RuntimeException, NEITHER extends
                    // DataIntegrityViolationException, so all three clauses above miss them.
                    // Logged because the result row cannot carry a stack trace.
                    log.error("CLONE_ROW_FAILED code={} semester={}", code, row.getSemester(), e);
                    results.add(new SubjectRowResult(code, row.getSemester(), "ERROR",
                        "Could not create this subject."));
                    errors++;
                }
            }
        }
        return new SubjectCloneResult(created, skipped, errors, results);
    }
}
