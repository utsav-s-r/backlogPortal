package com.college.backlog.controller;

import com.college.backlog.controller.dto.DepartmentRequest;
import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.controller.dto.RegistrationSummaryResponse;
import com.college.backlog.controller.dto.RegistrationEventResponse;
import com.college.backlog.controller.dto.RegistrationExportRequest;
import org.springframework.web.server.ResponseStatusException;
import com.college.backlog.model.Department;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Subject;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.RegistrationEventRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.service.RegistrationSpecification;
import com.college.backlog.service.PdfService;
import com.college.backlog.service.Semesters;
import com.college.backlog.service.SubjectService;
import com.college.backlog.service.CallerScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    // PROCTOR is dept-pinned like HOD/DEPT_OFFICE, plus restricted to assigned students
    // (see proctorRollNos below).
    private static final java.util.Set<UserRole> DEPT_ROLES =
        java.util.Set.of(UserRole.HOD, UserRole.DEPT_OFFICE, UserRole.PROCTOR);

    @Autowired
    private RegistrationRepository registrationRepository;

    @Autowired
    private CallerScope callerScope;

    @Autowired
    private com.college.backlog.service.RegistrationService registrationService;

    @Autowired
    private SubjectService subjectService;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private PdfService pdfService;

    @Autowired
    private RegistrationEventRepository registrationEventRepository;

    @Autowired
    private ExamCycleRepository examCycleRepository;

    @Autowired
    private com.college.backlog.service.ProctorScopeService proctorScope;

    // One DB lookup per request: endpoints needing both dept and proctor scope load the caller
    // once and pass the User to the helpers below, rather than each helper re-fetching the row.
    private User callerUser(Authentication auth) {
        return callerScope.requireActor(auth);
    }

    /**
     * Dept id a caller is pinned to; null ONLY for a genuinely unrestricted ADMIN/PRINCIPAL — which
     * is what every call site here assumes. "We can't tell" must never share that sentinel: an
     * unknown caller 401s in callerUser above, and a dept role without a department 403s below.
     */
    private Long resolveCallerDeptId(User user) {
        if (!DEPT_ROLES.contains(user.getRole())) return null;
        return callerScope.requireDepartmentId(user);
    }

    private Long resolveCallerDeptId(Authentication auth) {
        return resolveCallerDeptId(callerUser(auth));
    }

    /**
     * Assigned-student scope for a PROCTOR caller; null for every other role (unrestricted).
     * May be empty — a proctor with no assignments sees nothing, and callers must special-case
     * that because an empty IN list is not valid SQL.
     */
    private java.util.Set<String> proctorRollNos(User user) {
        return proctorScope.assignedRollNos(user);
    }

    /**
     * A dept-scoped caller (HOD/DEPT_OFFICE) may only touch a registration involving their
     * department — one of its subjects owned by or eligible for it. Mirrors checkDeptAccess in
     * RegistrationController (verify). A PROCTOR is scoped by student instead.
     */
    private void assertRegistrationInScope(Authentication auth, Registration reg) {
        User user = callerUser(auth);
        if (proctorScope.isProctor(user)) {
            proctorScope.assertSupervises(user, reg.getStudent().getRollNo());
            return;
        }
        Long callerDeptId = resolveCallerDeptId(user);
        if (callerDeptId == null) return; // ADMIN / PRINCIPAL: unrestricted
        boolean hasAccess = reg.getSubjects().stream().anyMatch(s -> {
            if (s.getDepartment() != null && callerDeptId.equals(s.getDepartment().getId())) return true;
            return s.getEligibleDepartments() != null &&
                   s.getEligibleDepartments().stream().anyMatch(d -> callerDeptId.equals(d.getId()));
        });
        if (!hasAccess) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This registration does not belong to your department.");
        }
    }

    // Cap on client-requested `size`, so it can't pull the whole append-only table in one shot.
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;

    @GetMapping("/registrations")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public Page<RegistrationSummaryResponse> getFilteredRegistrations(
            @RequestParam Optional<Long> subjectId,
            @RequestParam Optional<String> subjectType,
            @RequestParam Optional<String> searchQuery,
            @RequestParam Optional<Integer> semester,
            @RequestParam Optional<Long> departmentId,
            @RequestParam Optional<Long> examCycleId,
            @RequestParam Optional<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            Authentication authentication
    ) {
        User caller = callerUser(authentication);
        Long callerDeptId = resolveCallerDeptId(caller);
        java.util.Set<String> proctorRolls = proctorRollNos(caller);

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "registeredAt"));
        if (proctorRolls != null && proctorRolls.isEmpty()) {
            return Page.empty(pageable); // proctor with no assignments sees nothing
        }

        Specification<Registration> spec = RegistrationSpecification.builder()
                .subjectId(subjectId.orElse(null))
                .departmentId(effectiveDeptId(callerDeptId, departmentId.orElse(null)))
                .subjectType(subjectType.orElse(null))
                .searchQuery(searchQuery.orElse(null))
                .semester(parseSemester(semester.orElse(null)))
                .examCycleId(examCycleId.orElse(null))
                .status(parseStatus(status.orElse(null)))
                .studentRollNos(proctorRolls)
                .build();
        // mapping stays in the service transaction — `subjects` loads lazily during it
        return registrationService.listSummaries(spec, pageable);
    }

    // Dashboard stat cards: same filters as the list but WITHOUT status, so the cards show totals
    // for the filtered set whichever status tab is open. Count queries only; no rows hydrated.
    @GetMapping("/registrations/summary-counts")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public Map<String, Long> getRegistrationSummaryCounts(
            @RequestParam Optional<Long> subjectId,
            @RequestParam Optional<String> subjectType,
            @RequestParam Optional<String> searchQuery,
            @RequestParam Optional<Integer> semester,
            @RequestParam Optional<Long> departmentId,
            @RequestParam Optional<Long> examCycleId,
            Authentication authentication
    ) {
        User caller = callerUser(authentication);
        Long callerDeptId = resolveCallerDeptId(caller);
        java.util.Set<String> proctorRolls = proctorRollNos(caller);
        if (proctorRolls != null && proctorRolls.isEmpty()) {
            return Map.of("total", 0L, "submitted", 0L, "verified", 0L, "rejected", 0L);
        }
        // one GROUP BY over the filtered set (status left unset — the cards span every status)
        Map<RegistrationStatus, Long> counts = registrationService.countGroupedByStatus(
            RegistrationSpecification.builder()
                .subjectId(subjectId.orElse(null))
                .departmentId(effectiveDeptId(callerDeptId, departmentId.orElse(null)))
                .subjectType(subjectType.orElse(null))
                .searchQuery(searchQuery.orElse(null))
                .semester(parseSemester(semester.orElse(null)))
                .examCycleId(examCycleId.orElse(null))
                .studentRollNos(proctorRolls)
                .build());
        long submitted = counts.getOrDefault(RegistrationStatus.SUBMITTED, 0L);
        long verified = counts.getOrDefault(RegistrationStatus.VERIFIED, 0L);
        long rejected = counts.getOrDefault(RegistrationStatus.REJECTED, 0L);
        return Map.of(
            "total", submitted + verified + rejected,
            "submitted", submitted,
            "verified", verified,
            "rejected", rejected);
    }

    /**
     * Department to filter on. A dept-pinned caller's OWN department always wins — the request
     * parameter is a convenience for ADMIN/PRINCIPAL, who have no pin, and must never be able to
     * widen or redirect a HOD/DEPT_OFFICE/PROCTOR's scope.
     */
    private Long effectiveDeptId(Long callerDeptId, Long requestedDeptId) {
        return callerDeptId != null ? callerDeptId : requestedDeptId;
    }

    /** Optional semester filter; absent = every semester, out of range = 400. */
    private Integer parseSemester(Integer semester) {
        if (semester == null) {
            return null;
        }
        if (!Semesters.isStudiable(semester)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Semester must be between " + Semesters.MIN + " and " + Semesters.MAX + ".");
        }
        return semester;
    }

    /** Optional status filter; blank/absent = all statuses, unknown value = 400. */
    private RegistrationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RegistrationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status filter: " + status);
        }
    }

    @GetMapping("/registrations/{regId}/events")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public List<RegistrationEventResponse> getRegistrationEvents(@PathVariable String regId,
                                                                 Authentication authentication) {
        // dept-scoped roles may only read event trails their department can act on
        // — same rule as verify (RegistrationController)
        Registration reg = registrationRepository.findByRegId(regId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found with ID: " + regId));
        assertRegistrationInScope(authentication, reg);
        return registrationEventRepository.findByRegIdOrderByTimestampAsc(regId).stream()
            .map(e -> new RegistrationEventResponse(
                e.getAction() != null ? e.getAction().name() : null,
                e.getActor(),
                e.getActorRole() != null ? e.getActorRole().name() : null,
                e.getTimestamp() != null ? e.getTimestamp().toString() : null,
                e.getNote()))
            .collect(Collectors.toList());
    }

    @PostMapping("/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE')")
    public Subject addSubject(@Valid @RequestBody SubjectCreateRequest request, Authentication authentication) {
        // dept-scoped roles may only create subjects for their own department — server-side,
        // not just pinned in the UI
        Long callerDeptId = resolveCallerDeptId(authentication);
        if (callerDeptId != null && !callerDeptId.equals(request.getDeptId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You can only add subjects for your own department.");
        }
        return subjectService.createSubject(request);
    }

    // Read is open to all admin roles like the other reads here; the writes below stay ADMIN/PRINCIPAL.
    @GetMapping("/departments")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public List<Department> getDepartments() {
        return departmentRepository.findAll(Sort.by("deptName"));
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public Department addDepartment(@Valid @RequestBody DepartmentRequest request) {
        String code = request.getCode().trim().toUpperCase();
        if (departmentRepository.existsByCodeIgnoreCase(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A department with code '" + code + "' already exists.");
        }
        Department dept = new Department();
        dept.setDeptName(request.getDeptName().trim());
        dept.setCode(code);
        dept.setContactEmail(request.getContactEmail() != null ? request.getContactEmail().trim() : null);
        return departmentRepository.save(dept);
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public Department updateDepartment(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        Department dept = departmentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found with ID: " + id));
        // The @Version lock only guards a race within this request — it can't catch a stale-page
        // overwrite, since we just loaded the *current* row. So compare the version the client
        // last saw and reject if another admin saved in between.
        if (request.getVersion() != null && !request.getVersion().equals(dept.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This department was changed by someone else. Reload and try again.");
        }
        String code = request.getCode().trim().toUpperCase();
        // keeping the same code is fine; only block if another department owns it
        departmentRepository.findByCodeIgnoreCase(code).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A department with code '" + code + "' already exists.");
            }
        });
        dept.setDeptName(request.getDeptName().trim());
        dept.setCode(code);
        dept.setContactEmail(request.getContactEmail() != null ? request.getContactEmail().trim() : null);
        try {
            // saveAndFlush so the @Version backstop for the window between the check above and
            // the flush fires here, not later. Rethrown as 409 — the generic handler maps it to 500.
            return departmentRepository.saveAndFlush(dept);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This department was just changed by someone else. Reload and try again.");
        }
    }

    // ADMIN/PRINCIPAL like create/edit. 409 if still referenced by a subject (owning or eligible),
    // a staff user, or a student of that branch — deleting would break the FK and orphan them.
    // Discontinue an in-use department by not referencing it, not by deleting.
    @DeleteMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public void deleteDepartment(@PathVariable Long id) {
        Department dept = departmentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found with ID: " + id));

        if (subjectRepository.existsByDepartment_Id(id) || subjectRepository.existsByEligibleDepartments_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This department is referenced by existing subjects and cannot be deleted.");
        }
        if (userRepository.existsByDepartment_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This department is assigned to one or more staff users and cannot be deleted.");
        }
        if (dept.getCode() != null && studentRepository.existsByBranchIgnoreCase(dept.getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This department has students of its branch and cannot be deleted.");
        }
        departmentRepository.delete(dept);
    }

    @GetMapping("/subjects-for-filter")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public List<Subject> getSubjectsForFilter(
            @RequestParam Optional<String> subjectType,
            @RequestParam Optional<String> searchQuery,
            @RequestParam Optional<Integer> semester,
            @RequestParam Optional<Long> departmentId,
            Authentication authentication
    ) {
        User caller = callerUser(authentication);
        Long callerDeptId = resolveCallerDeptId(caller);
        java.util.Set<String> proctorRolls = proctorRollNos(caller);
        // Proctor scope belongs here too, not just on the list: the options are computed by joining
        // through Registration, so without it a proctor could read which subjects students they
        // don't supervise registered for (searchQuery makes it an oracle). Same short-circuit as
        // the list — an empty assigned set means nothing to offer.
        if (proctorRolls != null && proctorRolls.isEmpty()) {
            return List.of();
        }
        return subjectService.findDistinctSubjectsByRegistrationFilters(
                effectiveDeptId(callerDeptId, departmentId.orElse(null)),
                subjectType.orElse(null),
                searchQuery.orElse(null),
                parseSemester(semester.orElse(null)),
                proctorRolls);
    }

    /**
     * Summary report of the rows the caller names — either an explicit selection or a filter set.
     * POST, not GET: a selection of a few hundred regIds does not fit in a URL.
     */
    @PostMapping("/export-pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public void exportRegistrationsPdf(
            @Valid @RequestBody RegistrationExportRequest request,
            Authentication authentication,
            HttpServletResponse response
    ) throws Exception {
        User caller = callerUser(authentication);
        Long callerDeptId = resolveCallerDeptId(caller);
        java.util.Set<String> proctorRolls = proctorRollNos(caller);

        java.util.LinkedHashMap<String, String> context = new java.util.LinkedHashMap<>();
        List<Registration> registrations;

        if (proctorRolls != null && proctorRolls.isEmpty()) {
            registrations = List.of(); // proctor with no assignments has nothing to export
        } else if (request.hasSelection()) {
            // Selection wins over every filter — but NOT over scope. The id list came from a
            // browser, so the dept pin and proctor scope are re-applied in the same query; without
            // that, hand-editing the list would export any row in the college.
            registrations = registrationRepository.findAll(
                    RegistrationSpecification.builder()
                            .regIds(request.getRegIds())
                            .departmentId(callerDeptId)
                            .studentRollNos(proctorRolls)
                            .build(),
                    Sort.by(Sort.Direction.DESC, "registeredAt"));
            int asked = request.getRegIds().size();
            context.put("Scope", "Selected rows");
            // Say so when rows fall away rather than quietly shipping a shorter list — a silent
            // drop is indistinguishable from "those students never registered".
            context.put("Rows", registrations.size() == asked
                    ? String.valueOf(asked)
                    : registrations.size() + " of " + asked + " selected ("
                        + (asked - registrations.size()) + " not available to you)");
        } else {
            RegistrationStatus status = resolveExportStatus(request.getStatus());
            Long cycleId = resolveExportCycleId(request);
            Long deptId = effectiveDeptId(callerDeptId, request.getDepartmentId());

            registrations = registrationRepository.findAll(
                    RegistrationSpecification.builder()
                            .subjectId(request.getSubjectId())
                            .departmentId(deptId)
                            .subjectType(request.getSubjectType())
                            .searchQuery(request.getSearchQuery())
                            .semester(parseSemester(request.getSemester()))
                            .examCycleId(cycleId)
                            .status(status)
                            .studentRollNos(proctorRolls)
                            .build(),
                    Sort.by(Sort.Direction.DESC, "registeredAt"));

            describeFilters(context, request, cycleId, deptId, status, registrations.size());
        }

        // Build fully before committing the response. Streaming straight to the output stream sent
        // 200 + PDF headers first, so a mid-generation failure appended GlobalExceptionHandler's
        // JSON into the file and the browser saved a corrupt PDF. Summary rows are small, and the
        // per-student form PDF already buffers the same way.
        byte[] pdf = pdfService.generateRegistrationsSummaryPdf(registrations, context);

        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setContentLength(pdf.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"registrations-summary.pdf\"");
        response.getOutputStream().write(pdf);
    }

    /**
     * Status a filter-based export covers. Blank/absent = VERIFIED, the report's long-standing
     * default; the literal "ALL" spans every status, so an export can match the status tab the
     * admin is looking at instead of silently narrowing to verified.
     */
    private RegistrationStatus resolveExportStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return RegistrationStatus.VERIFIED;
        }
        if ("ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return parseStatus(raw);
    }

    /**
     * Which exam cycle a filter-based export covers: the requested one, else every cycle if asked
     * explicitly, else the active one. No active cycle and no explicit choice is a 400 — the old
     * code returned an empty PDF, which reads as "nobody registered".
     */
    private Long resolveExportCycleId(RegistrationExportRequest request) {
        if (request.getExamCycleId() != null) {
            return request.getExamCycleId();
        }
        if (request.isAllCycles()) {
            return null; // span every cycle, deliberately
        }
        return examCycleRepository.findByActiveTrue().map(ExamCycle::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No exam cycle is active. Pick a cycle to export, or choose All Cycles."));
    }

    /** Human-readable record of what the report covers, printed in the PDF header. */
    private void describeFilters(java.util.Map<String, String> context,
                                 RegistrationExportRequest request, Long cycleId, Long deptId,
                                 RegistrationStatus status, int rowCount) {
        context.put("Exam cycle", cycleId == null
                ? "All cycles"
                : examCycleRepository.findById(cycleId).map(ExamCycle::getName).orElse("#" + cycleId));
        if (deptId != null) {
            context.put("Department", departmentRepository.findById(deptId)
                    .map(Department::getDeptName).orElse("#" + deptId));
        }
        if (request.getSemester() != null) {
            context.put("Semester", String.valueOf(request.getSemester()));
        }
        if (request.getSubjectId() != null) {
            context.put("Subject", subjectRepository.findById(request.getSubjectId())
                    .map(s -> s.getCourseCode() + " " + s.getSubjectName())
                    .orElse("#" + request.getSubjectId()));
        } else if (request.getSubjectType() != null && !request.getSubjectType().isBlank()) {
            context.put("Subject type", request.getSubjectType());
        }
        if (request.getSearchQuery() != null && !request.getSearchQuery().isBlank()) {
            context.put("Search", request.getSearchQuery());
        }
        context.put("Status", status == null ? "All statuses" : status.name());
        context.put("Rows", String.valueOf(rowCount));
    }
}
