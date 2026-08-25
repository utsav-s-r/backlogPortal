package com.college.backlog.security;

import com.college.backlog.model.User;
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
 * Revokes a session whose account row is gone — <b>or whose role no longer matches the token</b>.
 * Only admin-type principals are checked — students are not rows in {@code users} at all.
 *
 * {@link JwtAuthenticationFilter} is fully stateless (username AND role come from the token), so a
 * deleted account keeps working until its token lapses. Verified: a deleted HOD's live cookie read
 * and wrote another department's subjects, and a deleted ADMIN opened and closed registration
 * college-wide. Answering 401 here revokes the session on the next request, for every admin
 * endpoint at once — including the ones that never resolve a scope (exam cycles, department CRUD),
 * which a per-controller check cannot reach. Keep this check HERE and nowhere else; moving it into
 * JwtAuthenticationFilter would apply it to STUDENT tokens, which have no {@code users} row.
 *
 * <p>Existence was only ever half the question, and the missing half was the same fail-open shape:
 * because role is immutable, changing one means delete + recreate under the same username, which
 * restores existence while the live token still carries the OLD role. See
 * {@code carriesCurrentRoleOf} for what that reaches and why this revokes instead of quietly
 * rewriting the authorities.
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
        if (isAdminRequest(auth, request) && !LOGOUT_PATH.equals(request.getRequestURI())) {
            // findById, not existsById: same single query, but it yields the row whose ROLE the
            // check below needs. Existence alone was never enough — see carriesCurrentRoleOf.
            User account = userRepository.findById(auth.getName()).orElse(null);
            if (account == null) {
                // The token is valid but its account is gone. 401, not 403: there is nothing to
                // grant, and the SPA's 401 interceptor signs them out — the correct outcome.
                write(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"message\":\"Unknown account. Please sign in again.\"}");
                return;
            }
            if (!carriesCurrentRoleOf(auth, account)) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"message\":\"Your account has changed. Please sign in again.\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(json);
    }

    /**
     * Does the token still claim the role the {@code users} row actually holds?
     *
     * <p>{@link JwtAuthenticationFilter} builds authorities from the token's {@code role} claim and
     * never consults the database, so every {@code @PreAuthorize} decides on the role baked in at
     * LOGIN. Staff role is immutable by owner decision, which means the only way to change one is
     * delete + recreate under the same username — and {@code username} is the PK, so recreating is
     * routine from Manage Users. Without this check an ADMIN demoted that way keeps {@code
     * ROLE_ADMIN} until the token lapses (up to the full 1h session), because the row exists again
     * and the existence check alone is satisfied.
     *
     * <p>What that reaches is not theoretical: {@code ExamCycleController} is class-annotated
     * {@code hasRole('ADMIN')} and its writes never resolve the caller at all, so the annotation —
     * i.e. the token — is the only gate on opening and closing registration college-wide.
     * {@code ProgressionController}'s bulk endpoints do call {@code CallerScope.requireActor}, but
     * that verifies the row EXISTS and has SOME role, never that it is the role the token claims,
     * so a college-wide {@code current_semester + 2} is reachable the same way.
     *
     * <p>A null role returns false, revoking the session. {@code users.role} is nullable and its
     * CHECK admits NULL, so such a row is storable; {@code CallerScope} answers 403 with a better
     * message, but only on endpoints that resolve a scope — the exam-cycle writes above do not, so
     * honouring the token's claim against a row that claims no role would leave exactly that hole
     * open. Fail closed here and let the clearer 403 apply wherever the request gets that far.
     *
     * <p>Revoke rather than silently substituting the row's authorities: the SPA caches
     * {@code adminRole} in sessionStorage at login and gates every admin page on it, so a
     * server-side swap would render one role's controls while the server enforced another's. 401
     * routes into the SPA's existing interceptor, and the re-login refreshes that cached role.
     */
    private boolean carriesCurrentRoleOf(Authentication auth, User account) {
        if (account.getRole() == null) {
            return false;
        }
        String current = "ROLE_" + account.getRole().name();
        return auth.getAuthorities().stream()
                .anyMatch(a -> current.equals(a.getAuthority()));
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
