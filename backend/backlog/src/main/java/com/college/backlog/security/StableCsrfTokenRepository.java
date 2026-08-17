package com.college.backlog.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * A {@link CookieCsrfTokenRepository} that keeps the {@code XSRF-TOKEN} cookie STABLE by ignoring
 * token-clearing writes (a {@code saveToken} with a null/empty token).
 *
 * Why: the app is stateless (JWT re-authenticated per request), so {@code SessionManagementFilter}
 * sees every request as a fresh authentication and runs {@code CsrfAuthenticationStrategy}, which
 * rotates the token by DELETING the cookie first. The replacement is deferred and never persisted
 * before the response commits, leaving the cookie deleted — so the next mutating request without
 * an intervening GET fails CSRF with 403, which the SPA reads as a dead session and logs the user
 * out. Seen as: fill two blank semesters on the Progression timeline, save one (ok), save the
 * second (403 -> logout).
 *
 * Safe to ignore that write: the token is a non-secret double-submit value validated
 * cookie-vs-header per request, and rotate-on-login guards session fixation, which doesn't apply
 * to a stateless cookie token. Logout clears the JWT cookie explicitly, not via this one.
 */
public class StableCsrfTokenRepository implements CsrfTokenRepository {

    private final CookieCsrfTokenRepository delegate;

    public StableCsrfTokenRepository(CookieCsrfTokenRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null || token.getToken() == null || token.getToken().isEmpty()) {
            return; // skip the clearing write so the cookie survives the per-request rotation
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
