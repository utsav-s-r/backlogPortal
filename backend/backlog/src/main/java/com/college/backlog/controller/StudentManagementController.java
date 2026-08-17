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
import com.college.backlog.service.CallerScope;
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
import java.util.stream.Collectors;

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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A student with USN " + rollNo + " already exists.");
        }
        Student saved;
        try {
            saved = studentService.createStudent(req);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
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
            saved = studentService.updateStudent(student, req);
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
            studentService.resetDob(student, req.getDateOfBirth());
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
            studentService.deleteStudent(student);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ---- bulk import ----

    @PostMapping("/import")
    public BatchResult importRows(@RequestBody StudentImportRequest req, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        proctorScope.rejectProctor(actor,
            "Proctors cannot import student accounts — claim existing students instead.");
        String callerDeptCode = callerDeptCode(actor);
        List<ProgressionRowResult> results = new ArrayList<>();
        int created = 0, skipped = 0, errors = 0;

        List<StudentImportRow> rows = req.getRows() == null ? List.of() : req.getRows();
        for (StudentImportRow row : rows) {
            String roll = studentService.normalizeUsn(row.getRollNo());
            int currentSem = firstNonNull(row.getCurrentSemester(), req.getDefaultCurrentSemester(), 0);
            int entrySem = firstNonNull(row.getEntrySemester(), req.getDefaultEntrySemester(), 1);
            try {
                // USN + scope + branch + ranges up front, so the dry-run sees the same errors
                studentService.validateUsn(roll);
                if (callerDeptCode != null && !callerDeptCode.equalsIgnoreCase(studentDeptCode(roll))) {
                    throw new IllegalArgumentException("Outside your department's scope.");
                }
                studentService.resolveBranchDept(roll);
                studentService.validateSemesters(currentSem, entrySem);
                if (row.getDateOfBirth() == null) {
                    throw new IllegalArgumentException("Date of birth is required.");
                }

                if (studentRepository.existsById(roll)) {
                    results.add(new ProgressionRowResult(roll, currentSem, "SKIPPED_EXISTS", null));
                    skipped++;
                } else if (req.isDryRun()) {
                    results.add(new ProgressionRowResult(roll, currentSem, "WOULD_CREATE", null));
                    created++;
                } else {
                    StudentCreateRequest create = new StudentCreateRequest();
                    create.setRollNo(roll);
                    create.setName(row.getName());
                    create.setPhone(row.getPhone());
                    create.setDateOfBirth(row.getDateOfBirth());
                    create.setCurrentSemester(currentSem);
                    create.setEntrySemester(entrySem);
                    studentService.createStudent(create);
                    results.add(new ProgressionRowResult(roll, currentSem, "CREATED", null));
                    created++;
                }
            } catch (IllegalArgumentException e) {
                results.add(new ProgressionRowResult(roll, currentSem, "ERROR", e.getMessage()));
                errors++;
            } catch (ResponseStatusException e) {
                // keep the nested reason (e.g. a 409 naming the conflict); the generic catch below
                // would flatten it, since ResponseStatusException is itself a RuntimeException
                results.add(new ProgressionRowResult(roll, currentSem, "ERROR", e.getReason()));
                errors++;
            } catch (RuntimeException e) {
                // an unexpected per-row failure (e.g. a DB constraint) becomes an ERROR row, never
                // aborting the batch or surfacing as a request-level 4xx/5xx — each createStudent
                // is its own REQUIRES_NEW tx, so one rollback doesn't poison the rest. Logged
                // because the row message cannot carry a stack trace.
                log.error("STUDENT_IMPORT_ROW_FAILED rollNo={}", roll, e);
                results.add(new ProgressionRowResult(roll, currentSem, "ERROR", "Could not import this row."));
                errors++;
            }
        }
        return new BatchResult(req.isDryRun(), created, skipped, errors, results);
    }

    // ---- helpers ----

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

    private int firstNonNull(Integer a, Integer b, int fallback) {
        if (a != null) return a;
        if (b != null) return b;
        return fallback;
    }
}
