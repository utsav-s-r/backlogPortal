package com.college.backlog.service;

import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Identifying the caller, in one place. Sibling of {@link ProctorScopeService}, which answers the
 * next question (which STUDENTS may this caller touch).
 *
 * This existed as a byte-identical private helper in nine controllers, and it is the primitive that
 * failed identically in five of them: the resolvers used to return a permissive {@code null} when
 * the caller could not be identified, and every call site reads {@code null} as "ADMIN/PRINCIPAL,
 * unrestricted". Extracted so the fail-closed contract is stated once and can be unit-tested —
 * private controller helpers cannot be, and this repo has no controller tests.
 *
 * Deliberately NOT extracted: each controller's own {@code DEPT_ROLES} set. They genuinely differ
 * ({HOD, DEPT_OFFICE, PROCTOR} in most, {HOD, DEPT_OFFICE} where PROCTOR is excluded by
 * {@code @PreAuthorize} or handled earlier), so a single shared set would silently widen or narrow
 * one of them — the exact bug class this whole pass removed.
 */
@Service
public class CallerScope {

    @Autowired
    private UserRepository userRepository;

    /**
     * The authenticated caller's row. 401 — never a null sentinel — when it cannot be identified:
     * {@link com.college.backlog.security.JwtAuthenticationFilter} is stateless, so a token outlives
     * a deleted account, and "we don't know who this is" must never be confused with "nothing
     * restricts them".
     */
    public User requireActor(Authentication auth) {
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        User user = userRepository.findById(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
        if (user.getRole() == null) {
            // users.role is nullable in the DB and its CHECK admits NULL (NULL = ANY(...) is NULL,
            // not false), so a hand-edited row is storable. Without this, every DEPT_ROLES.contains
            // call NPEs — Set.of() rejects a null lookup — and a broken account reads as a 500.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No role assigned to your account. Contact admin.");
        }
        return user;
    }

    /**
     * The department a dept-scoped caller is pinned to. Call only after deciding the role IS
     * dept-scoped; a role that isn't has no department by design, and 403 would be wrong.
     */
    public Department requireDepartment(User user) {
        if (user.getDepartment() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No department assigned to your account");
        }
        return user.getDepartment();
    }

    /** {@link #requireDepartment} when only the id is needed. */
    public Long requireDepartmentId(User user) {
        return requireDepartment(user).getId();
    }

    /** {@link #requireDepartment} when only the code is needed (USN branch matching). */
    public String requireDepartmentCode(User user) {
        return requireDepartment(user).getCode();
    }
}
