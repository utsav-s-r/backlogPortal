package com.college.backlog.controller;

import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.controller.dto.BatchResult;
import com.college.backlog.controller.dto.SubjectImportRequest;
import com.college.backlog.controller.dto.SubjectRowResult;
import com.college.backlog.controller.dto.SubjectUpdateRequest;
import com.college.backlog.model.Subject;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.service.AdminAuditService;
import org.springframework.transaction.annotation.Transactional;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.service.AcademicYears;
import com.college.backlog.service.SubjectImportService;
import com.college.backlog.service.SubjectService;
import com.college.backlog.service.SubjectSpecification;
import com.college.backlog.service.CallerScope;
import jakarta.validation.Valid;
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

import java.util.Optional;
import java.util.Set;

/**
 * The subject catalog: create, list, edit, delete, and CSV import. ADMIN/PRINCIPAL act on any
 * department, HOD/DEPT_OFFICE are pinned to their own; PROCTOR is excluded entirely by the
 * class-level {@code @PreAuthorize}, which is the only layer denying them (SecurityConfig's
 * {@code /api/admin/**} rule admits PROCTOR).
 */
@RestController
@RequestMapping("/api/admin/subjects")
@PreAuthorize("hasAnyRole('ADMIN','PRINCIPAL','HOD','DEPT_OFFICE')")
public class SubjectController {

    @Autowired
    private AdminAuditService auditService;

    @Autowired
    private CallerScope callerScope;

    private static final Set<UserRole> DEPT_ROLES = Set.of(UserRole.HOD, UserRole.DEPT_OFFICE);

    // Mirrors AdminController: cap so `size` can't pull the whole catalog in one request.
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;

    @Autowired private SubjectRepository subjectRepository;
    @Autowired private SubjectService subjectService;
    @Autowired private SubjectImportService subjectImportService;
    @Autowired private DepartmentRepository departmentRepository;

    // Spring Page envelope ({content, totalPages, totalElements, number, ...}), matching
    // /registrations. Was an unbounded findAll, which timed out clients once the catalog grew.
    @GetMapping
    public Page<Subject> list(
            @RequestParam Optional<Long> deptId,
            @RequestParam Optional<Integer> academicYearOffered,
            @RequestParam Optional<Integer> semester,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            Authentication auth) {
        Long callerDeptId = resolveCallerDeptId(auth);
        // dept-scoped callers are pinned to their own dept, ignoring any requested deptId
        Long effectiveDeptId = callerDeptId != null ? callerDeptId : deptId.orElse(null);
        SubjectSpecification spec = new SubjectSpecification(
            effectiveDeptId, academicYearOffered.orElse(null), semester.orElse(null));
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(
            Sort.Order.desc("academicYearOffered"),
            Sort.Order.asc("semester"),
            Sort.Order.asc("subjectName")));
        return subjectRepository.findAll(spec, pageable);
    }

    /**
     * Create one subject. Lives here with the rest of subject CRUD; the dept-scope guard is inline
     * because {@link SubjectService#createSubject} takes no caller department at all.
     *
     * <p>No method-level {@code @PreAuthorize}: the class-level one is the identical role set. That
     * annotation is the ONLY layer denying PROCTOR — SecurityConfig's {@code /api/admin/**} rule
     * admits them — so it is load-bearing, not decoration.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // @Transactional so the audit row joins the service's transaction — a create must never land
    // without its record (P3-9).
    @Transactional
    public Subject addSubject(@Valid @RequestBody SubjectCreateRequest request, Authentication auth) {
        // dept-scoped roles may only create subjects for their own department — server-side, not
        // just pinned in the UI. Same resolver the import path uses, so the refusal has one spelling.
        User actor = callerScope.requireActor(auth);
        requireWritableDept(actor, request.getDeptId());
        Subject saved = subjectService.createSubject(request);
        auditService.record(AdminAuditAction.SUBJECT_CREATE, actor,
                AuditTargetType.SUBJECT, String.valueOf(saved.getId()),
                "code=" + saved.getCourseCode() + " sem=" + saved.getSemester());
        return saved;
    }

    @PutMapping("/{id}")
    // @Transactional so the audit row joins the service's transaction rather than committing on its
    // own — the edit must never land without its record (P3-9).
    @Transactional
    public Subject update(@PathVariable Long id,
                          @Valid @RequestBody SubjectUpdateRequest request,
                          Authentication auth) {
        Subject saved = subjectService.updateSubject(id, request, resolveCallerDeptId(auth));
        auditService.record(AdminAuditAction.SUBJECT_UPDATE, callerScope.requireActor(auth),
                AuditTargetType.SUBJECT, String.valueOf(id),
                "code=" + saved.getCourseCode() + " sem=" + saved.getSemester());
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id, Authentication auth) {
        // Read the identifying values BEFORE the delete: the audit row carries no FK, so this is
        // the only place they survive.
        String detail = subjectRepository.findById(id)
                .map(sub -> "code=" + sub.getCourseCode() + " sem=" + sub.getSemester())
                .orElse("unknown subject");
        subjectService.deleteSubject(id, resolveCallerDeptId(auth));
        auditService.record(AdminAuditAction.SUBJECT_DELETE, callerScope.requireActor(auth),
                AuditTargetType.SUBJECT, String.valueOf(id), detail);
    }

    /**
     * Bulk-create subjects from a pasted CSV — the first-load path, where clone can't help because
     * there is no previous year to copy. The academic year is batch-level and stamped on every row;
     * {@code dryRun} previews without writing.
     *
     * <p>NOT @Transactional, matching the clone apply: the service commits each row separately by
     * design, so there is no enclosing transaction to join and the audit row records what actually
     * happened, after it happened. A dry run writes nothing, so it records nothing.
     */
    @PostMapping("/import")
    public BatchResult<SubjectRowResult> importSubjects(@RequestBody SubjectImportRequest req,
                                              Authentication auth) {
        User actor = callerScope.requireActor(auth);
        Long deptId = requireWritableDept(actor, req.getDeptId());
        try {
            AcademicYears.assertInRange(req.getAcademicYearOffered());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        BatchResult<SubjectRowResult> result = subjectImportService.importRows(
                deptId, req.getAcademicYearOffered(), req.isDryRun(), req.getRows());

        if (!req.isDryRun()) {
            // ONE row for the whole operation, as with SUBJECT_CLONE: importing a catalog is a
            // single administrative act.
            auditService.record(AdminAuditAction.SUBJECT_IMPORT, actor, AuditTargetType.DEPARTMENT,
                    String.valueOf(deptId),
                    "year=" + req.getAcademicYearOffered() + " created=" + result.getCreated()
                        + " skipped=" + result.getSkipped() + " errors=" + result.getErrors());
        }
        return result;
    }

    /**
     * The department a subject write lands in. A dept-pinned caller gets their own and is REFUSED
     * (403) if they named another — not silently redirected, which would report success for rows
     * the admin believes went somewhere else. An unrestricted caller must name a real one.
     *
     * <p>Takes the already-loaded {@code actor} rather than {@code Authentication}: resolving it
     * again here is a second {@code users} lookup per request, which AdminController's callerUser
     * comment calls out as the thing to avoid.
     */
    private Long requireWritableDept(User actor, Long requestedDeptId) {
        Long pinned = resolveCallerDeptId(actor);
        if (pinned != null) {
            if (requestedDeptId != null && !requestedDeptId.equals(pinned)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Outside your department's scope.");
            }
            return pinned;
        }
        // 400, not 404: the id comes from the request BODY, so the URL's resource exists and it is
        // the submitted reference that is wrong. Matches the sibling body-referenced lookups.
        if (requestedDeptId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is required.");
        }
        if (!departmentRepository.existsById(requestedDeptId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department.");
        }
        return requestedDeptId;
    }

    /** Dept id a caller is pinned to; null ONLY for a genuinely unrestricted ADMIN/PRINCIPAL —
     *  "we can't tell who this is" 401s, and a dept role without a department 403s. */
    private Long resolveCallerDeptId(Authentication auth) {
        return resolveCallerDeptId(callerScope.requireActor(auth));
    }

    /** Same, for a handler that already loaded the caller — one users lookup per request. */
    private Long resolveCallerDeptId(User user) {
        if (!DEPT_ROLES.contains(user.getRole())) return null;
        return callerScope.requireDepartmentId(user);
    }
}
