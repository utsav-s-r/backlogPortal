package com.college.backlog.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The whole point of {@link StableCsrfTokenRepository}: a clearing write (saveToken with a
 * null/empty token) must NOT reach the cookie repository, so the per-request
 * CsrfAuthenticationStrategy rotation can't delete the XSRF-TOKEN cookie and break
 * back-to-back mutations. Real tokens still pass through.
 */
class StableCsrfTokenRepositoryTest {

    private final CookieCsrfTokenRepository delegate = mock(CookieCsrfTokenRepository.class);
    private final StableCsrfTokenRepository repo = new StableCsrfTokenRepository(delegate);

    private final HttpServletRequest request = new MockHttpServletRequest();
    private final HttpServletResponse response = new MockHttpServletResponse();

    private CsrfToken token(String value) {
        return new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", value);
    }

    @Test
    void nullTokenSaveIsIgnored() {
        repo.saveToken(null, request, response);
        verify(delegate, never()).saveToken(any(), any(), any());
    }

    @Test
    void emptyTokenSaveIsIgnored() {
        CsrfToken empty = mock(CsrfToken.class); // DefaultCsrfToken forbids an empty value
        when(empty.getToken()).thenReturn("");
        repo.saveToken(empty, request, response);
        verify(delegate, never()).saveToken(any(), any(), any());
    }

    @Test
    void realTokenSaveIsDelegated() {
        CsrfToken t = token("abc123");
        repo.saveToken(t, request, response);
        verify(delegate).saveToken(t, request, response);
    }

    @Test
    void generateAndLoadAreDelegated() {
        repo.generateToken(request);
        verify(delegate).generateToken(request);
        repo.loadToken(request);
        verify(delegate).loadToken(request);
    }
}
