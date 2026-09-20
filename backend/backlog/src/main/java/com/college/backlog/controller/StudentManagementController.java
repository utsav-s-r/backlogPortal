package com.college.backlog.controller;

import com.college.backlog.controller.dto.*;
import com.college.backlog.model.Student;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.service.ProctorScopeService;
import com.college.backlog.service.StudentManagementService;
import com.college.backlog.service.StudentSpecification;
import com.college.backlog.service.Usn;
import com.college.backlog.service.Batches;
import com.college.backlog.service.CallerScope;
import com.college.backlog.service.Constraints;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Admin student-account management. Authorization mirrors ProgressionController and is enforced
 * here on the server (the UI only mirrors it): ADMIN/PRINCIPAL act on any department;
 * HOD/DEPT_OFFICE are pinned to their own by USN branch code; PROCTOR is pinned the same way plus
 * hard-scoped to assigned students — the list shows only those, edit/reset-DOB require
 * supervision, and create/import/delete are refused (a proctor "removes a student" by unassigning
 * in ProctorAssignmentController, never by deleting the account).
 *
 * DOB is never returned — it is a write-only credential. See docs/adr/backlog-progression.md.
 */
@RestController
@RequestMapping("/api/admin/students")
@PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
public class StudentManagementController {

    @Autowired
    private CallerScope callerScope;

    private static final Logger log = LoggerFactory.getLogger(StudentManagementController.class);

    private static final Set<UserRole> DEPT_ROLES =
        Set.of(UserRole.HOD, UserRole.DEPT_OFFICE, UserRole.PROCTOR);

    // Mirrors AdminController: cap so `size` can't pull the whole roster in one request.
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;

    @Autowired private StudentRepository studentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentManagementService studentService;
    @Autowired private ProctorScopeService proctorScope;

    // ---- list ----

    // Spring Page envelope ({content, totalPages, totalElements, number, ...}), matching
    // /registrations. Was an unbounded findAll, which timed out clients once the roster grew.
    @GetMapping
    public Page<StudentSummaryResponse> list(
            @RequestParam Optional<Long> deptId,
            @RequestParam Optional<Integer> admissionYear,
            @RequestParam Optional<Integer> semester,
            @RequestParam Optional<String> query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            Authentication auth) {
        User actor = callerScope.requireActor(auth);
        String deptCode = effectiveDeptCode(actor, deptId.orElse(null));
        String rollNoLike = usnPattern(deptCode, admissionYear.orElse(null));

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("rollNo"));

        // proctor: roster restricted to assigned students. An empty IN list is not valid SQL, so
        // no assignments short-circuits to an empty page rather than an unfiltered query.
        Set<String> assigned = proctorScope.assignedRollNos(actor);
        if (assigned != null && assigned.isEmpty()) {
            return Page.empty(pageable);
        }
        StudentSpecification spec = new StudentSpecification(
            rollNoLike, semester.orElse(null), query.orElse(null), assigned);
        return studentRepository.findAll(spec, pageable).map(this::toSummary);
    }

    // ---- create ----

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudentSummaryResponse create(@Valid @RequestBody StudentCreateRequest req, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        proctorScope.rejectProctor(actor,
            "Proctors cannot create student accounts — claim existing students instead.");
        String rollNo = studentService.normalizeUsn(req.getRollNo());
        req.setRollNo(rollNo);
        assertInScope(actor, rollNo);
        if (studentRepository.existsById(rollNo)) {
            throw studentExists(rollNo);
        }
        Student saved;
        try {
            saved = studentService.createStudent(req, actor.getUsername());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Created concurrently between the check above and the insert: the same 409. Any other
            // constraint goes on to GlobalExceptionHandler.
            if (!Constraints.isViolationOf(e, Constraints.STUDENT_ROLL_NO)) {
                throw e;
            }
            throw studentExists(rollNo);
        }
        return toSummary(saved);
    }

    // ---- edit ----

    @PutMapping("/{rollNo}")
    public StudentSummaryResponse update(@PathVariable String rollNo,
                                         @Valid @RequestBody StudentUpdateRequest req,
                                         Authentication auth) {
        User actor = callerScope.requireActor(auth);
        Student student = loadInScope(actor, rollNo);
        Student saved;
        try {
            saved = studentService.updateStudent(student, req, actor.getUsername());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return toSummary(saved);
    }

    // ---- reset DOB ----

    @PostMapping("/{rollNo}/reset-dob")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetDob(@PathVariable String rollNo,
                         @Valid @RequestBody ResetDobRequest req,
                         Authentication auth) {
        User actor = callerScope.requireActor(auth);
        Student student = loadInScope(actor, rollNo);
        try {
            studentService.resetDob(student, req.getDateOfBirth(), actor.getUsername());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---- delete (only if unreferenced) ----

    @DeleteMapping("/{rollNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String rollNo, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        proctorScope.rejectProctor(actor,
            "Proctors cannot delete student accounts — remove the student from your supervision instead.");
        Student student = loadInScope(actor, rollNo);
        try {
            studentService.deleteStudent(student, actor.getUsername());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ---- bulk import ----

    @PostMapping("/import")
    public BatchResult<ProgressionRowResult> importRows(@RequestBody StudentImportRequest req, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        proctorScope.rejectProctor(actor,
            "Proctors cannot import student accounts — claim existing students instead.");
        String callerDeptCode = callerDeptCode(actor);
        List<ProgressionRowResult> results = new ArrayList<>();
        int created = 0, skipped = 0, errors = 0;

        List<StudentImportRow> rows = req.getRows() == null ? List.of() : req.getRows();
        // after the scope checks, so an out-of-scope caller gets 403 regardless of batch size
        Batches.assertWithinLimit(rows.size(), "rows");

        for (StudentImportRow row : rows) {
            String roll = studentService.normalizeUsn(row.getRollNo());
            // Null = NEITHER the row nor the batch supplied it. Deliberately carried as null to
            // the check below rather than defaulted: entrySemester used to fall back to 1, which
            // is a LEGAL value, so it validated and a student was created. A lateral entrant
            // imported without one silently got a window starting at semester 1 — backlogs for
            // semesters never studied — and createStudent's seedLinearTimeline then wrote
            // student_semester_terms rows for them, which are write-once and only correctable by
            // hand on the Semesters panel. currentSemester fell back to 0, which cannot validate,
            // so that half already failed; it just blamed the wrong thing.
            // Stays an Integer all the way onto the result row too: ProgressionRowResult.semester
            // is nullable and BatchResultTable renders null as an em dash, so a row that named no
            // semester reports none. There is no semester 0 to print.
            Integer askedCurrent = row.getCurrentSemester() != null
                    ? row.getCurrentSemester() : req.getDefaultCurrentSemester();
            Integer askedEntry = row.getEntrySemester() != null
                    ? row.getEntrySemester() : req.getDefaultEntrySemester();
            try {
                // USN before scope: a malformed USN has no branch code to scope on
                studentService.validateUsn(roll);
                if (callerDeptCode != null && !callerDeptCode.equalsIgnoreCase(studentDeptCode(roll))) {
                    throw new IllegalArgumentException("Outside your department's scope.");
                }
                // Absent is its own failure, not a value. Semesters' own messages name the
                // legal SHAPE ("must be an odd semester"), which reads as a wrong number to a
                // caller who sent none — so say which field is missing and where it can come
                // from. Inside the try, so a dry run reports it exactly as the real import does.
                if (askedCurrent == null) {
                    throw new IllegalArgumentException(
                        "Current semester is required — set the column or the batch default.");
                }
                if (askedEntry == null) {
                    throw new IllegalArgumentException(
                        "Entry semester is required — set the column or the batch default "
                            + "(1 for normal intake, 3, 5 or 7 for lateral entry).");
                }
                StudentCreateRequest create = new StudentCreateRequest();
                create.setRollNo(roll);
                create.setName(row.getName());
                create.setPhone(row.getPhone());
                create.setDateOfBirth(row.getDateOfBirth());
                create.setCurrentSemester(askedCurrent);
                create.setEntrySemester(askedEntry);
                // createStudent's own rule list, run for dry run and real import alike
                studentService.validateNewStudent(create);

                if (studentRepository.existsById(roll)) {
                    results.add(new ProgressionRowResult(roll, askedCurrent, "SKIPPED_EXISTS", null));
                    skipped++;
                } else if (req.isDryRun()) {
                    results.add(new ProgressionRowResult(roll, askedCurrent, "WOULD_CREATE", null));
                    created++;
                } else {
                    studentService.createStudent(create, actor.getUsername());
                    results.add(new ProgressionRowResult(roll, askedCurrent, "CREATED", null));
                    created++;
                }
            } catch (NumberFormatException e) {
                // NFE extends IllegalArgumentException, so without this clause it lands below and
                // its raw message ("For input string: \"null\"") reaches the admin as if the ROW were
                // malformed — a server bug dressed as a data problem, counted as a bad row instead
                // of logged. Must precede the IAE clause; the reverse does not compile.
                log.error("STUDENT_IMPORT_ROW_FAILED rollNo={}", roll, e);
                results.add(new ProgressionRowResult(roll, askedCurrent, "ERROR", "Could not import this row."));
                errors++;
            } catch (IllegalArgumentException e) {
                // Only this method's own validation throws IAE, with curated literal messages, so
                // surfacing getMessage() is safe here.
                results.add(new ProgressionRowResult(roll, askedCurrent, "ERROR", e.getMessage()));
                errors++;
            } catch (ResponseStatusException e) {
                // keep the nested reason (e.g. a 409 naming the conflict); the generic catch below
                // would flatten it, since ResponseStatusException is itself a RuntimeException
                results.add(new ProgressionRowResult(roll, askedCurrent, "ERROR", e.getReason()));
                errors++;
            } catch (DataIntegrityViolationException e) {
                // Above the catch-all. The USN was created concurrently between existsById and the
                // insert: the same outcome as that check. Any other constraint is a real failure.
                if (Constraints.isViolationOf(e, Constraints.STUDENT_ROLL_NO)) {
                    results.add(new ProgressionRowResult(roll, askedCurrent, "SKIPPED_EXISTS", null));
                    skipped++;
                } else {
                    log.error("STUDENT_IMPORT_ROW_FAILED rollNo={}", roll, e);
                    results.add(new ProgressionRowResult(roll, askedCurrent, "ERROR", "Could not import this row."));
                    errors++;
                }
            } catch (RuntimeException e) {
                // an unexpected per-row failure (e.g. a DB constraint) becomes an ERROR row, never
                // aborting the batch or surfacing as a request-level 4xx/5xx — each createStudent
                // is its own REQUIRES_NEW tx, so one rollback doesn't poison the rest. Logged
                // because the row message cannot carry a stack trace.
                log.error("STUDENT_IMPORT_ROW_FAILED rollNo={}", roll, e);
                results.add(new ProgressionRowResult(roll, askedCurrent, "ERROR", "Could not import this row."));
                errors++;
            }
        }
        return new BatchResult<>(req.isDryRun(), created, skipped, errors, results);
    }

    // ---- helpers ----

    private ResponseStatusException studentExists(String rollNo) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "A student with USN " + rollNo + " already exists.");
    }

    /** Dept code a dept-scoped caller is pinned to; null ONLY for a genuinely unrestricted
     *  ADMIN/PRINCIPAL. A dept role without a department is unscopeable, not unrestricted. */
    private String callerDeptCode(User actor) {
        if (!DEPT_ROLES.contains(actor.getRole())) return null;
        return callerScope.requireDepartmentCode(actor);
    }

    private String studentDeptCode(String rollNo) {
        return Usn.branchCode(rollNo);
    }

    /** The dept code to filter by: the caller's own (if scoped), else a requested deptId. */
    private String effectiveDeptCode(User actor, Long requestedDeptId) {
        String callerCode = callerDeptCode(actor);
        if (callerCode != null) return callerCode;
        if (requestedDeptId != null) {
            return departmentRepository.findById(requestedDeptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department."))
                .getCode();
        }
        return null;
    }

    /** USN LIKE pattern from dept code + admission year, or null when neither is set. */
    private String usnPattern(String deptCode, Integer admissionYear) {
        if (deptCode == null && admissionYear == null) return null;
        String yy = admissionYear != null ? String.format("%02d", admissionYear % 100) : "__";
        String cc = deptCode != null ? deptCode.toUpperCase() : "__";
        return "1MS" + yy + cc + "%";
    }

    private void assertInScope(User actor, String rollNo) {
        String code = callerDeptCode(actor);
        if (code != null && !code.equalsIgnoreCase(studentDeptCode(rollNo))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Outside your department's scope.");
        }
    }

    private Student loadInScope(User actor, String rollNo) {
        String roll = studentService.normalizeUsn(rollNo);
        assertInScope(actor, roll);
        proctorScope.assertSupervises(actor, roll); // proctor: assignment scope on top of dept scope
        return studentRepository.findByRollNo(roll)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found: " + roll));
    }

    private StudentSummaryResponse toSummary(Student s) {
        return new StudentSummaryResponse(
            s.getRollNo(), s.getName(), s.getEmail(), s.getPhone(),
            s.getBranch(), s.getCurrentSemester(), s.getEntrySemester());
    }

}
