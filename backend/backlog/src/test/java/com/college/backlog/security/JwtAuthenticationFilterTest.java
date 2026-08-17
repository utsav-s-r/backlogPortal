package com.college.backlog.security;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
