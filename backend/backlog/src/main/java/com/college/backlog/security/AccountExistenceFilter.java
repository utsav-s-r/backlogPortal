package com.college.backlog.security;

import com.college.backlog.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Revokes a session whose account row is gone. Only admin-type principals are checked — students
 * are not rows in {@code users} at all.
 *
 * {@link JwtAuthenticationFilter} is fully stateless (username AND role come from the token), so a
 * deleted account keeps working until its token lapses. Verified: a deleted HOD's live cookie read
 * and wrote another department's subjects, and a deleted ADMIN opened and closed registration
 * college-wide. Answering 401 here revokes the session on the next request, for every admin
 * endpoint at once — including the ones that never resolve a scope (exam cycles, department CRUD),
 * which a per-controller check cannot reach. Keep this check HERE and nowhere else; moving it into
 * JwtAuthenticationFilter would apply it to STUDENT tokens, which have no {@code users} row.
 *
 * Logout is exempt: it merely expires the session cookie, and blocking it left exactly the accounts
 * that must be signed out unable to do so, with the cookie alive until it timed out on its own.
 *
 * This filter also used to gate a forced first-login password change. That was removed with the
 * {@code must_change_password} column (V8) — accounts now carry a derived default password
 * (username + "4321") that their holder may change at any time, and nothing has to be enforced
 * per-request to make that work.
 */
@Component
public class AccountExistenceFilter extends OncePerRequestFilter {

    private static final String LOGOUT_PATH = "/api/auth/logout";
    private static final Set<String> ADMIN_AUTHORITIES =
            Set.of("ROLE_ADMIN", "ROLE_PRINCIPAL", "ROLE_HOD", "ROLE_DEPT_OFFICE", "ROLE_PROCTOR");

    private final UserRepository userRepository;

    public AccountExistenceFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isAdminRequest(auth, request)
                && !LOGOUT_PATH.equals(request.getRequestURI())
                && !userRepository.existsById(auth.getName())) {
            // The token is valid but its account is gone. 401, not 403: there is nothing to
            // grant, and the SPA's 401 interceptor signs them out — the correct outcome.
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "{\"message\":\"Unknown account. Please sign in again.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(json);
    }

    /** An authenticated admin-type principal, on a request that may carry account state. */
    private boolean isAdminRequest(Authentication auth, HttpServletRequest request) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false; // never block CORS preflight
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_AUTHORITIES.contains(a.getAuthority()));
    }
}
