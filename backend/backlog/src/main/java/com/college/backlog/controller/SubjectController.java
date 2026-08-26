package com.college.backlog.controller;

import com.college.backlog.controller.dto.SubjectUpdateRequest;
import com.college.backlog.model.Subject;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.service.AdminAuditService;
import org.springframework.transaction.annotation.Transactional;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.SubjectRepository;
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
 * View / edit / delete the subject catalog — the read-and-maintain side; create lives in
 * {@link AdminController} (POST /api/admin/subjects). ADMIN/PRINCIPAL act on any department,
 * HOD/DEPT_OFFICE are pinned to their own.
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

    /** Dept id a caller is pinned to; null ONLY for a genuinely unrestricted ADMIN/PRINCIPAL —
     *  "we can't tell who this is" 401s, and a dept role without a department 403s. */
    private Long resolveCallerDeptId(Authentication auth) {
        User user = callerScope.requireActor(auth);
        if (!DEPT_ROLES.contains(user.getRole())) return null;
        return callerScope.requireDepartmentId(user);
    }
}
