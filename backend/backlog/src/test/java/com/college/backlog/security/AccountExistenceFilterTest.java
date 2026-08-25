package com.college.backlog.security;

import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountExistenceFilterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountExistenceFilter filter = new AccountExistenceFilter(userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** The `users` row the filter reads. Its role is the one that must win over the token's. */
    private void accountRowIs(String username, UserRole role) {
        User u = new User();
        u.setUsername(username);
        u.setRole(role);
        when(userRepository.findById(username)).thenReturn(Optional.of(u));
    }

    private void noAccountRowFor(String username) {
        when(userRepository.findById(username)).thenReturn(Optional.empty());
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod(method);
        req.setRequestURI(uri);
        return req;
    }

    // The token outlives the row: JwtAuthenticationFilter is stateless, so without this a deleted
    // account keeps its authority until the token lapses. The resolvers 401 on an unknown caller
    // too, but they only protect endpoints that RESOLVE a scope — exam cycles and department CRUD
    // never load the caller at all, so this filter is the only thing covering them.
    @Test
    void deletedAccountIsRejectedEvenWithAValidToken() throws Exception {
        authenticateAs("hodcse", "HOD");
        noAccountRowFor("hodcse");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("GET", "/api/admin/subjects"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unknown account");
        verify(chain, never()).doFilter(any(), any());
    }

    // Change-password is not exempt: a deleted account has no password to set, so it must not be
    // the one endpoint that still honours a dead token.
    @Test
    void deletedAccountCannotReachTheChangePasswordEndpointEither() throws Exception {
        authenticateAs("admin", "ADMIN");
        noAccountRowFor("admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("POST", "/api/auth/change-password"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void existingAdminPassesThrough() throws Exception {
        authenticateAs("admin", "ADMIN");
        accountRowIs("admin", UserRole.ADMIN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = request("GET", "/api/admin/registrations");
        filter.doFilter(req, response, chain);

        verify(chain, times(1)).doFilter(req, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * THE privilege-retention case. Role is immutable by owner decision, so changing one means
     * delete + recreate under the same username — which restores existence while the live cookie
     * still carries the old role. An existence-only check passes this; every {@code @PreAuthorize}
     * then decides on the token, and ExamCycleController's writes resolve no caller at all, so
     * ROLE_ADMIN on a DEPT_OFFICE row would still open and close registration college-wide.
     */
    @Test
    void aDemotedAccountCannotKeepUsingItsOldRolesToken() throws Exception {
        authenticateAs("admin-user", "ADMIN");          // the token, minted before the demotion
        accountRowIs("admin-user", UserRole.DEPT_OFFICE); // what the row says now
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("POST", "/api/admin/exam-cycles"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Your account has changed");
        verify(chain, never()).doFilter(any(), any());
    }

    /** The same check has to bind upward, or a recreate that PROMOTES hands a session powers the
     *  SPA never rendered controls for — it caches adminRole at login. */
    @Test
    void aPromotedAccountAlsoHasToSignInAgain() throws Exception {
        authenticateAs("dept-user", "DEPT_OFFICE");
        accountRowIs("dept-user", UserRole.ADMIN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("GET", "/api/admin/registrations"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    /**
     * users.role is nullable and its CHECK admits NULL (NULL = ANY(...) is NULL, not false), so
     * such a row is storable. CallerScope answers a clearer 403 — but only on endpoints that
     * resolve a scope, and the exam-cycle writes do not. Honouring the token against a row that
     * claims no role would leave exactly that hole open, so fail closed here.
     */
    @Test
    void aRowWithNoRoleAtAllCannotHaveItsTokenTrusted() throws Exception {
        authenticateAs("broken-user", "ADMIN");
        accountRowIs("broken-user", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("POST", "/api/admin/exam-cycles"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    /** An unchanged dept role must still pass — the check narrows nothing when nothing changed. */
    @Test
    void anUnchangedDeptRolePassesThrough() throws Exception {
        authenticateAs("hod-user", "HOD");
        accountRowIs("hod-user", UserRole.HOD);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = request("GET", "/api/admin/subjects");
        filter.doFilter(req, response, chain);

        verify(chain, times(1)).doFilter(req, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // Logout only expires the cookie. Gating it stranded exactly the accounts that must be signed
    // out — and it costs no DB lookup, so the repository is never touched.
    @Test
    void aDeletedAccountCanStillLogOut() throws Exception {
        authenticateAs("hodcse", "HOD");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = request("POST", "/api/auth/logout");
        filter.doFilter(req, response, chain);

        verify(chain, times(1)).doFilter(req, response);
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(userRepository);
    }

    @Test
    void corsPreflightIsNeverBlocked() throws Exception {
        authenticateAs("admin", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = request("OPTIONS", "/api/admin/registrations");
        filter.doFilter(req, response, chain);

        verify(chain, times(1)).doFilter(req, response);
        // short-circuits before any DB lookup
        verifyNoInteractions(userRepository);
    }

    // Students authenticate with the same cookie machinery but are not rows in `users`, so
    // checking existence for them would 401 every student on every request.
    @Test
    void studentsAreNotSubjectToThisFilter() throws Exception {
        authenticateAs("1MS22CS001", "STUDENT");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = request("GET", "/api/student/me");
        filter.doFilter(req, response, chain);

        verify(chain, times(1)).doFilter(req, response);
        verifyNoInteractions(userRepository);
    }

    @Test
    void unauthenticatedRequestsAreNotGatedHere() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req = request("GET", "/api/admin/registrations");
        filter.doFilter(req, response, chain);

        verify(chain, times(1)).doFilter(req, response);
        verifyNoInteractions(userRepository);
    }
}
