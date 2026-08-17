package com.college.backlog.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieServiceTest {

    private SessionCookieService service(boolean secure) {
        SessionCookieService s = new SessionCookieService();
        ReflectionTestUtils.setField(s, "secure", secure);
        return s;
    }

    @Test
    void studentEndpointsResolveToTheStudentCookieEverythingElseToAdmin() {
        SessionCookieService s = service(true);
        assertThat(s.cookieNameForPath("/api/student/me")).isEqualTo(SessionCookieService.STUDENT_COOKIE);
        assertThat(s.cookieNameForPath("/api/student/auth/logout")).isEqualTo(SessionCookieService.STUDENT_COOKIE);
        assertThat(s.cookieNameForPath("/api/register")).isEqualTo(SessionCookieService.STUDENT_COOKIE);
        // admin-side paths — including the admin-actioned verify under /api/register
        assertThat(s.cookieNameForPath("/api/register/verify/abc")).isEqualTo(SessionCookieService.ADMIN_COOKIE);
        assertThat(s.cookieNameForPath("/api/admin/registrations")).isEqualTo(SessionCookieService.ADMIN_COOKIE);
        assertThat(s.cookieNameForPath("/api/auth/logout")).isEqualTo(SessionCookieService.ADMIN_COOKIE);
        assertThat(s.cookieNameForPath(null)).isEqualTo(SessionCookieService.ADMIN_COOKIE);
    }

    @Test
    void readReturnsTheNamedCookieValueOrNull() {
        SessionCookieService s = service(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionCookieService.ADMIN_COOKIE, "jwt-abc"));

        assertThat(s.read(request, SessionCookieService.ADMIN_COOKIE)).isEqualTo("jwt-abc");
        assertThat(s.read(request, SessionCookieService.STUDENT_COOKIE)).isNull();
        assertThat(s.read(new MockHttpServletRequest(), SessionCookieService.ADMIN_COOKIE)).isNull();
    }

    @Test
    void writeSetsAnHttpOnlyLaxSecureCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service(true).write(response, SessionCookieService.ADMIN_COOKIE, "jwt-abc");

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains(SessionCookieService.ADMIN_COOKIE + "=jwt-abc");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("Secure");
    }

    @Test
    void secureFlagIsOmittedForLocalHttpDev() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service(false).write(response, SessionCookieService.STUDENT_COOKIE, "jwt-xyz");

        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Secure");
    }

    @Test
    void clearExpiresTheCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service(true).clear(response, SessionCookieService.ADMIN_COOKIE);

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains(SessionCookieService.ADMIN_COOKIE + "=");
        assertThat(setCookie).contains("Max-Age=0");
    }
}
