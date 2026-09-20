package com.college.backlog.security;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

    JwtAuthenticationFilterTest() {
        ReflectionTestUtils.setField(filter, "jwtService", jwtService);
        // real cookie service — read() only inspects request cookies, no config needed
        ReflectionTestUtils.setField(filter, "sessionCookieService", new SessionCookieService());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest adminRequestWithSessionCookie(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/admin/registrations");
        if (token != null) {
            request.setCookies(new Cookie(SessionCookieService.ADMIN_COOKIE, token));
        }
        return request;
    }

    /** Regression: parsing an expired/tampered token throws. The filter must swallow it, leave
     *  the request anonymous, and still invoke the chain — an escape surfaces as 500, not 401. */
    @Test
    void expiredTokenIsTreatedAsAnonymousAndDoesNotThrow() throws Exception {
        MockHttpServletRequest request = adminRequestWithSessionCookie("expired.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtService.getUsernameFromToken("expired.token.value"))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(jwtService, never()).getRoleFromToken(org.mockito.ArgumentMatchers.anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noSessionCookieJustContinuesTheChain() throws Exception {
        MockHttpServletRequest request = adminRequestWithSessionCookie(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenSetsAuthentication() throws Exception {
        MockHttpServletRequest request = adminRequestWithSessionCookie("good.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtService.getUsernameFromToken("good.token.value")).thenReturn("admin");
        when(jwtService.validateToken("good.token.value")).thenReturn(true);
        when(jwtService.getRoleFromToken("good.token.value")).thenReturn("ADMIN");
        when(jwtService.getIssuedAtFromToken("good.token.value")).thenReturn(Instant.now());

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        // Carries the issue time to AccountExistenceFilter, which refuses a token older than its
        // account. Without these details that filter cannot age the token and lets it through.
        assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
                .isInstanceOf(JwtSessionDetails.class);
    }

    /**
     * A token with no issue time cannot be aged against the account's session_valid_from, so it
     * is treated as unauthenticated rather than trusted. generateToken always sets one, so the
     * only way here is a token this application did not mint — which must not be the one shape
     * that bypasses revocation.
     */
    @Test
    void aTokenWithNoIssuedAtIsNotAuthenticated() throws Exception {
        MockHttpServletRequest request = adminRequestWithSessionCookie("no.iat.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtService.getUsernameFromToken("no.iat.token")).thenReturn("admin");
        when(jwtService.validateToken("no.iat.token")).thenReturn(true);
        when(jwtService.getRoleFromToken("no.iat.token")).thenReturn("ADMIN");
        when(jwtService.getIssuedAtFromToken("no.iat.token")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
