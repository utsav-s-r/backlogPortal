package com.college.backlog.controller;

import com.college.backlog.controller.dto.SubjectCloneApplyRequest;
import com.college.backlog.controller.dto.SubjectCloneResult;
import com.college.backlog.controller.dto.SubjectClonePreviewRequest;
import com.college.backlog.controller.dto.SubjectClonePreviewResponse;
import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.service.AcademicYears;
import com.college.backlog.service.SubjectCloneService;
import com.college.backlog.service.CallerScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Clone a department's subject offerings into a new academic year. ADMIN/PRINCIPAL may clone for
 * any department, HOD/DEPT_OFFICE are pinned to their own, mirroring {@link ProgressionController}.
 * The server forces the target year and re-validates the department, so a crafted request can't
 * write outside the caller's scope or into the wrong year.
 */
@RestController
@RequestMapping("/api/admin/subjects/clone")
@PreAuthorize("hasAnyRole('ADMIN','PRINCIPAL','HOD','DEPT_OFFICE')")
public class SubjectCloneController {

    @Autowired
    private CallerScope callerScope;

    private static final Set<UserRole> DEPT_ROLES = Set.of(UserRole.HOD, UserRole.DEPT_OFFICE);

    @Autowired private SubjectCloneService cloneService;
    @Autowired private DepartmentRepository departmentRepository;

    @PostMapping("/preview")
    public SubjectClonePreviewResponse preview(@RequestBody SubjectClonePreviewRequest req, Authentication auth) {
        Department dept = resolveDept(auth, req.getDeptId());
        validateYear(req.getSourceYear());
        validateYear(req.getTargetYear());
        return cloneService.preview(dept.getId(), req.getSourceYear(), req.getTargetYear(), req.getSemesters());
    }

    @PostMapping("/apply")
    public SubjectCloneResult apply(@RequestBody SubjectCloneApplyRequest req, Authentication auth) {
        Department dept = resolveDept(auth, req.getDeptId());
        validateYear(req.getTargetYear());
        return cloneService.apply(dept.getId(), req.getTargetYear(), req.getRows());
    }

    /** Department the caller may act on: own for HOD/DEPT_OFFICE, any for ADMIN/PRINCIPAL. */
    private Department resolveDept(Authentication auth, Long requestedDeptId) {
        User actor = callerScope.requireActor(auth);
        if (DEPT_ROLES.contains(actor.getRole())) {
            Department own = callerScope.requireDepartment(actor);
            if (requestedDeptId != null && !requestedDeptId.equals(own.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Outside your department's scope.");
            }
            return own;
        }
        if (requestedDeptId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is required.");
        }
        return departmentRepository.findById(requestedDeptId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department."));
    }

    /** Shared range rule; rethrown as a 400 because IllegalArgumentException has no handler. */
    private void validateYear(int year) {
        try {
            AcademicYears.assertInRange(year);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
