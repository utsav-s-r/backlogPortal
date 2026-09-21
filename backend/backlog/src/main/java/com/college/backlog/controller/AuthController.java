package com.college.backlog.controller;

import com.college.backlog.controller.dto.ChangePasswordRequest;
import com.college.backlog.controller.dto.ChangeUsernameRequest;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.security.JwtService;
import com.college.backlog.security.SessionCookieService;
import com.college.backlog.service.AdminAuditService;
import com.college.backlog.service.CallerScope;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Set<UserRole> DEPT_ROLES =
        Set.of(UserRole.HOD, UserRole.DEPT_OFFICE, UserRole.PROCTOR);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CallerScope callerScope;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SessionCookieService sessionCookieService;

    @Autowired
    private AdminAuditService auditService;

    /** Compared against when there is no real hash. Made by the app's own encoder so its algorithm
     *  and cost match every stored hash; a hardcoded one drifts silently if the encoder changes. */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Map<String, String> body,
                                     HttpServletResponse response) {

        // Objects.toString, not getOrDefault: that default covers only an ABSENT key, and a JSON null
        // must get the same 400 below, not an NPE the catch-all turns into a 500.
        String username = Objects.toString(body.get("username"), "").trim();
        String password = Objects.toString(body.get("password"), "");

        if (username.isEmpty() || password.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        User user = userRepository.findByUsername(username).orElse(null);
        // Unknown user and wrong password are deliberately indistinguishable — same 401, same text,
        // same time: matchesPassword runs one bcrypt check on every path, a null user included.
        if (!matchesPassword(user, password)) {
            throw invalidCredentials();
        }

        // users.role is nullable; Set.of(...).contains(null) NPEs. After the password check, so only
        // a caller who proved the credentials learns the account is broken. Same refusal CallerScope makes.
        if (user.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has no role assigned. Contact admin.");
        }

        if (DEPT_ROLES.contains(user.getRole())) {
            if (user.getDepartment() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No department assigned to this account. Contact admin.");
            }
            String requestedDeptId = body.get("departmentId");
            if (requestedDeptId != null && !requestedDeptId.isBlank()) {
                try {
                    long reqId = Long.parseLong(requestedDeptId);
                    if (reqId != user.getDepartment().getId()) {
                        // 400, not 401: the password already matched: what's wrong is the
                        // departmentId FIELD, not the credentials. Nothing extra leaks — the
                        // caller has already proven who they are.
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid department for this account");
                    }
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid departmentId format");
                }
            }
        }

        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        // httpOnly cookie, not the body, so page scripts can't read the token
        sessionCookieService.write(response, SessionCookieService.ADMIN_COOKIE, token);

        Map<String, String> body2 = new HashMap<>();
        body2.put("message", "Login success");
        body2.put("role", user.getRole().name());
        // httpOnly, so JS can't read `exp`: expiresIn lets the SPA derive an absolute expiry for its
        // sign-out timer + warning banner. NOT a refresh — sessions are fixed, non-renewable (JwtService).
        body2.put("expiresIn", String.valueOf(jwtService.secondsUntilExpiry(token)));
        if (user.getDepartment() != null) {
            body2.put("departmentId", String.valueOf(user.getDepartment().getId()));
            body2.put("departmentName", user.getDepartment().getDeptName());
        }
        return body2;
    }

    /** Log out: expire the admin session cookie. */
    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletResponse response) {
        sessionCookieService.clear(response, SessionCookieService.ADMIN_COOKIE);
        Map<String, String> resp = new HashMap<>();
        resp.put("message", "Logged out");
        return resp;
    }

    /** Self-service password change for any authenticated admin-type user; requires the current
     *  password. The only way off the derived default issued by create/reset (username + "4321"),
     *  so every admin role must be able to reach it — the dashboard header links it. */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth,
                                              HttpServletResponse response) {
        User user = callerScope.requireActor(auth);

        if (!matchesPassword(user, req.getCurrentPassword())) {
            // 400, not 401: the session is valid — a mistyped `currentPassword` is a bad FIELD, not
            // a dead session. api.js signs out on a 401 from this endpoint, so a 401 here would
            // eject the user over a typo.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from the current one");
        }

        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        // Every session opened with the OLD password dies, this tab included. Deliberate, and the
        // same shape as change-username below: the cookie is cleared here rather than left for
        // AccountExistenceFilter to 401 on the next request. Re-issuing a token here instead
        // would keep this tab alive at the cost of a THIRD endpoint that mints sessions — login
        // is the only place that should, and the point of changing a password is that the new
        // one is required.
        user.revokeExistingSessions();
        userRepository.save(user);
        sessionCookieService.clear(response, SessionCookieService.ADMIN_COOKIE);

        Map<String, String> resp = new HashMap<>();
        resp.put("message", "Password changed. Please sign in again.");
        resp.put("signedOut", "true");
        return resp;
    }

    /**
     * Self-service rename for any authenticated admin-type user; requires the current password, as
     * change-password does — a live session alone must not be enough to change a sign-in credential.
     *
     * <p><b>This signs the caller out, deliberately.</b> The JWT subject is the username (V4 moved
     * only the DB key to a surrogate id), so the live token no longer resolves; the cookie is
     * cleared here rather than left for {@code AccountExistenceFilter} to 401 on the next request.
     * Same outcome, but chosen instead of stumbled into — and the SPA caches {@code adminUsername}
     * at login, so it has to re-authenticate to refresh it either way.
     *
     * <p>The password is NOT reset to the new derived default. An account still on
     * {@code oldname + "4321"} keeps that password after the rename; resetting a credential as a
     * side effect of renaming would be a worse surprise than the drift.
     */
    @PostMapping("/change-username")
    @Transactional
    public Map<String, String> changeUsername(@Valid @RequestBody ChangeUsernameRequest req,
                                              Authentication auth, HttpServletResponse response) {
        User user = callerScope.requireActor(auth);

        if (!matchesPassword(user, req.getCurrentPassword())) {
            // 400, not 401, for the reason spelled out on changePassword: the session is valid, a
            // mistyped currentPassword is a bad FIELD.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        String newUsername = req.getNewUsername();
        String oldUsername = user.getUsername();
        if (newUsername.equals(oldUsername)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New username must be different from the current one");
        }
        if (userRepository.existsByUsername(newUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A user with that username already exists");
        }

        // Recorded BEFORE the mutation so `actor` is who they were when they acted — the audit
        // convention everywhere else. Same transaction: the rename must never commit without it.
        auditService.record(AdminAuditAction.USER_RENAME, user, AuditTargetType.USER, newUsername,
                "self-rename from=" + oldUsername);

        user.setUsername(newUsername);
        user.revokeExistingSessions(); // one rule for every identity change — see the reset path
        userRepository.save(user);
        sessionCookieService.clear(response, SessionCookieService.ADMIN_COOKIE);

        Map<String, String> resp = new HashMap<>();
        resp.put("message", "Username changed. Please sign in again.");
        resp.put("signedOut", "true");
        return resp;
    }

    /** Exactly one bcrypt check per call, whatever the outcome. Skipping it for a missing user or
     *  hash answers tens of ms faster than a wrong password, which enumerates usernames. */
    private boolean matchesPassword(User user, String rawPassword) {
        String storedPassword = user == null ? null : user.getPassword();
        if (storedPassword == null || storedPassword.isBlank()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            return false;
        }
        // bcrypt only — legacy plaintext rows are upgraded once at startup by DataSeeder
        return passwordEncoder.matches(rawPassword, storedPassword);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
}
