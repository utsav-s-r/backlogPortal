package com.college.backlog.controller;

import com.college.backlog.controller.dto.CreateUserRequest;
import com.college.backlog.controller.dto.UserResponse;
import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.service.CallerScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin-facing user management, enforced here on the server — the UI only mirrors it.
 *
 * Who may manage whom:
 *   ADMIN       -> any user, any role
 *   PRINCIPAL   -> HOD, DEPT_OFFICE, PROCTOR (any department)
 *   HOD         -> DEPT_OFFICE, PROCTOR in their own department only
 *   DEPT_OFFICE -> no access
 *   PROCTOR     -> no access
 *
 * Passwords are never returned, and none needs to be: create and reset both install the same
 * derived default, {@code username + "4321"}, which the manager can simply tell the holder. The
 * username minimum of 4 ({@code CreateUserRequest}) keeps that default at or above the 8-character
 * floor {@code ChangePasswordRequest} imposes on chosen passwords. Nothing forces a change — the
 * holder changes it when they like via {@code POST /api/auth/change-password}.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {

    // Roles that must carry a department. Doubles as PRINCIPAL's manageable set.
    private static final Set<UserRole> DEPT_ROLES =
        Set.of(UserRole.HOD, UserRole.DEPT_OFFICE, UserRole.PROCTOR);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CallerScope callerScope;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD')")
    public List<UserResponse> listUsers(Authentication auth) {
        User actor = callerScope.requireActor(auth);
        return userRepository.findAll().stream()
                .filter(u -> !u.getUsername().equals(actor.getUsername())) // self managed via change-password
                .filter(u -> canManage(actor, u))
                .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()))
                .map(UserResponse::from)
                .collect(Collectors.toList());
    }

    // 201 like every other create here (subjects, departments, students, exam cycles). The sibling
    // /{username}/reset stays 200 on purpose — it returns a credential, it creates nothing.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD')")
    public Map<String, String> createUser(@Valid @RequestBody CreateUserRequest req, Authentication auth) {
        User actor = callerScope.requireActor(auth);

        // already trimmed by the DTO setter, so this is the same value @Size validated
        String username = req.getUsername();
        UserRole role = UserRole.fromNullable(req.getRole());

        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        if (!canManageRole(actor, role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to create this kind of user");
        }
        if (userRepository.existsById(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with that username already exists");
        }

        Department department = null;
        if (DEPT_ROLES.contains(role)) {
            if (req.getDepartmentId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is required for this role");
            }
            department = departmentRepository.findById(req.getDepartmentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department"));
            // HOD may only create within their own department
            if (actor.getRole() == UserRole.HOD && !sameDept(actor, department.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only manage users in your own department.");
            }
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(defaultPasswordFor(username)));
        user.setRole(role);
        user.setDepartment(department);
        userRepository.save(user);

        return accountResponse(user);
    }

    @PostMapping("/{username}/reset")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD')")
    public Map<String, String> resetPassword(@PathVariable String username, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = loadManageableTarget(actor, username);

        if (target.getUsername().equals(actor.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use Change Password to update your own account");
        }

        target.setPassword(passwordEncoder.encode(defaultPasswordFor(target.getUsername())));
        userRepository.save(target);

        return accountResponse(target);
    }

    // 204 like every other delete here (subjects, departments, students, proctor assignments). The
    // body it used to return was never read by the only caller.
    @DeleteMapping("/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD')")
    public void deleteUser(@PathVariable String username, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = loadManageableTarget(actor, username);

        if (target.getUsername().equals(actor.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete your own account");
        }
        // never leave the system with no administrator
        if (target.getRole() == UserRole.ADMIN && userRepository.countByRole(UserRole.ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete the last administrator account");
        }

        userRepository.delete(target);
    }

    // ---- helpers ----

    /** Loads a target the actor is allowed to manage, or throws 404/403. */
    private User loadManageableTarget(User actor, String username) {
        User target = userRepository.findById(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!canManage(actor, target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to manage this user");
        }
        return target;
    }

    private boolean canManage(User actor, User target) {
        if (!canManageRole(actor, target.getRole())) {
            return false;
        }
        // HOD is additionally constrained to their own department
        if (actor.getRole() == UserRole.HOD) {
            Long deptId = target.getDepartment() == null ? null : target.getDepartment().getId();
            return deptId != null && sameDept(actor, deptId);
        }
        return true;
    }

    private boolean canManageRole(User actor, UserRole targetRole) {
        if (actor.getRole() == null) {
            return false;
        }
        switch (actor.getRole()) {
            case ADMIN:
                return true; // ADMIN may manage any role
            case PRINCIPAL:
                return DEPT_ROLES.contains(targetRole);
            case HOD:
                return targetRole == UserRole.DEPT_OFFICE || targetRole == UserRole.PROCTOR;
            default:
                return false;
        }
    }

    private boolean sameDept(User actor, Long deptId) {
        return actor.getDepartment() != null && actor.getDepartment().getId().equals(deptId);
    }

    /** The password create and reset both install. Derived, not random, so it never has to be
     *  transported or shown once — the manager already knows it from the username. */
    static String defaultPasswordFor(String username) {
        return username + "4321";
    }

    // No password here: it is derivable from the username, so there is nothing to reveal and
    // nothing that becomes unrecoverable once this response is dismissed.
    private Map<String, String> accountResponse(User user) {
        Map<String, String> resp = new HashMap<>();
        resp.put("username", user.getUsername());
        resp.put("role", user.getRole() != null ? user.getRole().name() : null);
        if (user.getDepartment() != null) {
            resp.put("departmentName", user.getDepartment().getDeptName());
        }
        return resp;
    }
}
