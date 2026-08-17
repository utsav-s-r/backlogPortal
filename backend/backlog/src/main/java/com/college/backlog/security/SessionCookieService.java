package com.college.backlog.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The JWT rides in an httpOnly cookie — unreadable by JS, so not exfiltratable via XSS — rather
 * than the Authorization header + sessionStorage. Two cookies keep the admin and student audiences
 * independent, so both can be signed in at once in one browser.
 *
 * Cookies are session-scoped (no Max-Age, cleared on browser close; the JWT's own expiry bounds
 * validity anyway), SameSite=Lax, and Secure in production — off only for local http dev via
 * {@code app.cookie.secure=false}.
 */
@Component
public class SessionCookieService {

    public static final String ADMIN_COOKIE = "ADMIN_SESSION";
    public static final String STUDENT_COOKIE = "STUDENT_SESSION";

    // Secure by default; local http dev sets APP_COOKIE_SECURE=false, else the browser drops the
    // cookie over http and login silently fails.
    @Value("${app.cookie.secure:true}")
    private boolean secure;

    /** Which session cookie a request path uses: student endpoints vs the rest. */
    public String cookieNameForPath(String path) {
        if (path != null && (path.startsWith("/api/student") || path.equals("/api/register"))) {
            return STUDENT_COOKIE;
        }
        return ADMIN_COOKIE;
    }

    public void write(HttpServletResponse response, String name, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(name, token).build().toString());
    }

    /** Expire the cookie (logout). */
    public void clear(HttpServletResponse response, String name) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(name, "").maxAge(0).build().toString());
    }

    public String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
    }
}
