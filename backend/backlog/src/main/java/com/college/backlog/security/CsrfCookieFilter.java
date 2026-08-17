package com.college.backlog.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the deferred CsrfToken so {@link org.springframework.security.web.csrf.CookieCsrfTokenRepository}
 * actually writes the {@code XSRF-TOKEN} cookie: Spring Security 6 loads it lazily otherwise, and
 * the SPA never gets a cookie to echo as {@code X-XSRF-TOKEN}. Runs per request; cheap.
 *
 * The token is kept STABLE across requests by disabling per-request CsrfAuthenticationStrategy
 * rotation in {@link SecurityConfig} — under stateless JWT auth it fired every request and deleted
 * this cookie, breaking back-to-back mutations.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken(); // render the token -> repository writes the cookie
        }
        filterChain.doFilter(request, response);
    }
}
