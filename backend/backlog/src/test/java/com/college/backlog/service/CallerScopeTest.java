package com.college.backlog.service;

import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fail-closed contract these controllers used to get wrong nine times over. Every case below
 * was previously a permissive {@code null} that call sites read as "unrestricted ADMIN/PRINCIPAL".
 */
class CallerScopeTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CallerScope callerScope = new CallerScope();

    CallerScopeTest() {
        ReflectionTestUtils.setField(callerScope, "userRepository", userRepository);
    }

    private Authentication authFor(String username) {
        return new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_HOD")));
    }

    private ResponseStatusException thrownBy(Runnable r) {
        return (ResponseStatusException) org.assertj.core.api.Assertions.catchThrowable(r::run);
    }

    // ---- requireActor ----

    @Test
    void noAuthenticationIs401() {
        assertThatThrownBy(() -> callerScope.requireActor(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not authenticated");
        assertThat(thrownBy(() -> callerScope.requireActor(null)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The deleted-account case: JwtAuthenticationFilter is stateless, so the token outlives the row. */
    @Test
    void aTokenWhoseAccountIsGoneIs401NotAPermissiveNull() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        ResponseStatusException ex = thrownBy(() -> callerScope.requireActor(authFor("ghost")));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("Unknown account");
    }

    /** users.role is nullable in the DB and its CHECK admits NULL, so this row is storable — and
     *  Set.of(...).contains(null) would NPE into a 500 at every DEPT_ROLES check. */
    @Test
    void anAccountWithNoRoleIs403NotAnNpe() {
        User roleless = new User("broken", "hash", null);
        when(userRepository.findById("broken")).thenReturn(Optional.of(roleless));

        ResponseStatusException ex = thrownBy(() -> callerScope.requireActor(authFor("broken")));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getReason()).contains("No role assigned");
    }

    @Test
    void aKnownAccountIsReturned() {
        User hod = new User("hodcse", "hash", UserRole.HOD);
        when(userRepository.findById("hodcse")).thenReturn(Optional.of(hod));

        assertThat(callerScope.requireActor(authFor("hodcse"))).isSameAs(hod);
    }

    // ---- requireDepartment ----

    @Test
    void aDeptScopedRoleWithNoDepartmentIs403NotUnrestricted() {
        User hod = new User("hodcse", "hash", UserRole.HOD); // department left null

        ResponseStatusException ex = thrownBy(() -> callerScope.requireDepartment(hod));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getReason()).isEqualTo("No department assigned to your account");
    }

    @Test
    void theDepartmentIdAndCodeAccessorsAgreeWithTheDepartment() {
        Department cse = new Department(7L, "Computer Science", "cs@msrit.edu");
        cse.setCode("CS");
        User hod = new User("hodcse", "hash", UserRole.HOD);
        hod.setDepartment(cse);

        assertThat(callerScope.requireDepartment(hod)).isSameAs(cse);
        assertThat(callerScope.requireDepartmentId(hod)).isEqualTo(7L);
        assertThat(callerScope.requireDepartmentCode(hod)).isEqualTo("CS");
    }
}
