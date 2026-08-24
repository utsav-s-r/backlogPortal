package com.college.backlog.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionCookieServiceTest {

    private SessionCookieService service(boolean secure) {
        return service(secure, "Lax");
    }

    // sameSite is set explicitly rather than leaned on the field initialiser, so these tests assert
    // the CONFIGURED value reaches the cookie instead of a hardcoded default that happens to match.
    private SessionCookieService service(boolean secure, String sameSite) {
        SessionCookieService s = new SessionCookieService();
        ReflectionTestUtils.setField(s, "secure", secure);
        ReflectionTestUtils.setField(s, "sameSite", sameSite);
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

    // ---- app.cookie.same-site validation (validateCookiePolicy, @PostConstruct) ----
    // Every invalid value is otherwise silent: blank drops the attribute, a typo ships verbatim for
    // the browser to ignore, and a crafted string injects cookie attributes. Boot must refuse them.

    @Test
    void anUnrecognisedSameSiteFailsFastAtBoot() {
        assertThatThrownBy(() -> service(true, "Strct").validateCookiePolicy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be Lax, Strict or None");
    }

    @Test
    void aBlankSameSiteFailsFastRatherThanDroppingTheAttribute() {
        assertThatThrownBy(() -> service(true, "  ").validateCookiePolicy())
                .isInstanceOf(IllegalStateException.class);
    }

    /** The one combination browsers reject outright — must not be discoverable only in production. */
    @Test
    void sameSiteNoneWithoutSecureFailsFastAtBoot() {
        assertThatThrownBy(() -> service(false, "None").validateCookiePolicy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires app.cookie.secure=true");
    }

    /** None stays AVAILABLE: a future cross-origin deployment must be able to set it from env vars. */
    @Test
    void sameSiteNoneIsAcceptedWhenSecure() {
        SessionCookieService s = service(true, "None");
        s.validateCookiePolicy();

        MockHttpServletResponse response = new MockHttpServletResponse();
        s.write(response, SessionCookieService.ADMIN_COOKIE, "jwt-abc");
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=None");
    }

    /** Browsers compare the value case-insensitively, so a boot must not fail over "lax". */
    @Test
    void casingIsNormalisedRatherThanRejected() {
        SessionCookieService s = service(true, "lax");
        s.validateCookiePolicy();

        MockHttpServletResponse response = new MockHttpServletResponse();
        s.write(response, SessionCookieService.ADMIN_COOKIE, "jwt-abc");
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
    }

    // app.cookie.same-site is configurable so a cross-origin deployment needs no rebuild. Without
    // this, the property could be silently ignored and every cookie would still say Lax.
    @Test
    void sameSiteComesFromConfigurationNotAHardcodedLax() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service(true, "None").write(response, SessionCookieService.ADMIN_COOKIE, "jwt-abc");

        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=None");
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
