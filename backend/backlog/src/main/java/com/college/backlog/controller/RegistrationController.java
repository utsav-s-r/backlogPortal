package com.college.backlog.controller;

import com.college.backlog.controller.dto.StudentRegistrationRequest;
import com.college.backlog.controller.dto.VerificationResponse;
import com.college.backlog.model.ActorRole;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.service.ProctorScopeService;
import com.college.backlog.service.RegistrationService;
import com.college.backlog.service.CallerScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/register")
public class RegistrationController {

    private static final Set<UserRole> DEPT_ROLES = Set.of(UserRole.HOD, UserRole.DEPT_OFFICE);

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private CallerScope callerScope;

    @Autowired
    private RegistrationRepository registrationRepository;


    @Autowired
    private ProctorScopeService proctorScope;

    // Takes the already-loaded caller, so verify doesn't fetch the same row twice — once for
    // scoping, once for the audit actor role.
    private void checkDeptAccess(User user, Registration reg) {
        // a proctor is scoped by assigned STUDENT, not the subject's department
        if (user.getRole() == UserRole.PROCTOR) {
            proctorScope.assertSupervises(user, reg.getStudent().getRollNo());
            return;
        }
        if (!DEPT_ROLES.contains(user.getRole())) return; // ADMIN / PRINCIPAL: unrestricted
        // The STUDENT's department verifies, not the subject's. The form is signed by the
        // student's proctor and HOD, and this is that signature. Scoping by subject made the
        // authority multi-valued — a registration spanning three departments could be actioned by
        // any of them, first click wins — and let a CSE HOD sign off a Civil student's form while
        // the student's own HOD sometimes could not see it at all. VIEWING stays wider: every
        // involved department sees the row (RegistrationSpecification).
        String callerDept = callerScope.requireDepartmentCode(user);
        String studentBranch = reg.getStudent() != null ? reg.getStudent().getBranch() : null;
        if (studentBranch == null || !callerDept.equalsIgnoreCase(studentBranch)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the student's own department can verify this registration.");
        }
    }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public Map<String, String> register(@Valid @RequestBody StudentRegistrationRequest request,
                                        Authentication authentication) {
        // owner comes from the authenticated token, never the request body
        Registration reg = registrationService.register(
            authentication.getName(),
            request.getSubjectIds()
        );

        return Map.of(
            "regId", reg.getRegId(),
            "status", reg.getStatus().name()
        );
    }

    @PutMapping("/verify/{regId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public VerificationResponse verifyRegistration(
            @PathVariable String regId,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        Registration reg = registrationRepository.findByRegId(regId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found with ID: " + regId));

        // loaded once, for both the scope check and the audit actor role
        User caller = callerScope.requireActor(authentication);
        checkDeptAccess(caller, reg);

        // explicit known action required — never default a typo to VERIFIED
        String requested = body != null ? body.get("action") : null;
        RegistrationStatus action;
        if ("VERIFIED".equals(requested)) {
            action = RegistrationStatus.VERIFIED;
        } else if ("REJECTED".equals(requested)) {
            action = RegistrationStatus.REJECTED;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "action must be 'VERIFIED' or 'REJECTED'.");
        }

        String actor = authentication.getName();
        // UserRole is a by-name subset of ActorRole, so this mapping always resolves. No fallback:
        // an unidentifiable caller now 401s above rather than being audited as ADMIN.
        ActorRole actorRole = ActorRole.valueOf(caller.getRole().name());

        // status flip + audit event commit atomically; the pending-state check and the @Version
        // optimistic-lock backstop both run inside that transaction
        reg = registrationService.applyVerification(regId, action, actor, actorRole);

        return new VerificationResponse(
            reg.getRegId(),
            reg.getStudent().getName(),
            reg.getStudent().getRollNo(),
            reg.getStatus().name()
        );
    }

}