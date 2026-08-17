package com.college.backlog.controller;

import com.college.backlog.controller.dto.ChangePasswordRequest;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.security.JwtService;
import com.college.backlog.security.SessionCookieService;
import com.college.backlog.service.CallerScope;
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
import java.util.Set;

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

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Map<String, String> body,
                                     HttpServletResponse response) {

        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");

        if (username.isEmpty() || password.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        User user = userRepository.findById(username).orElse(null);
        // Unknown user and wrong password are deliberately indistinguishable — same 401, same text
        if (user == null || !matchesPassword(user, password)) {
            throw invalidCredentials();
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
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth) {
        User user = callerScope.requireActor(auth);

        if (!matchesPassword(user, req.getCurrentPassword())) {
            // 400, not 401: the session is valid — a mistyped `currentPassword` is a bad FIELD, not
            // a dead session. It survives as a 401 today only because api.js's admin-scoped URL
            // matcher happens not to cover /auth/change-password; widen that matcher later and a
            // typo would sign the user out mid-change.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from the current one");
        }

        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        Map<String, String> resp = new HashMap<>();
        resp.put("message", "Password changed");
        return resp;
    }

    private boolean matchesPassword(User user, String rawPassword) {
        String storedPassword = user.getPassword();
        if (storedPassword == null || storedPassword.isBlank()) {
            return false;
        }
        // bcrypt only — legacy plaintext rows are upgraded once at startup by DataSeeder
        return passwordEncoder.matches(rawPassword, storedPassword);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
}
