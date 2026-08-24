package com.college.backlog.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * The JWT rides in an httpOnly cookie — unreadable by JS, so not exfiltratable via XSS — rather
 * than the Authorization header + sessionStorage. Two cookies keep the admin and student audiences
 * independent, so both can be signed in at once in one browser.
 *
 * Cookies are session-scoped (no Max-Age, cleared on browser close; the JWT's own expiry bounds
 * validity anyway), SameSite=Lax, and Secure in production — off only for local http dev via
 * {@code app.cookie.secure=false}.
 *
 * <p>SameSite is {@code app.cookie.same-site}, and <b>Lax is the right default — this property
 * exists to make the value settable without a rebuild, not to invite changing it.</b> {@code Strict}
 * withholds the cookie on inbound links from email or the college site, logging students out for no
 * real CSRF gain given the double-submit token already covers cross-site POST. {@code None} is only
 * ever correct if the SPA and API end up on different registrable domains, and it REQUIRES
 * {@code app.cookie.secure=true} — browsers silently drop a {@code SameSite=None} cookie sent
 * without {@code Secure}, which presents as "login succeeds, every later request is 401".
 *
 * <p><b>All three legal values stay available on purpose.</b> The validation below rejects typos,
 * not choices: a future maintainer who genuinely moves the SPA off this origin must be able to set
 * {@code None} from the host's env vars, which is the whole reason this stopped being a hardcoded
 * string. What it will not let them do is ship {@code None} without {@code Secure}.
 */
@Component
public class SessionCookieService {

    public static final String ADMIN_COOKIE = "ADMIN_SESSION";
    public static final String STUDENT_COOKIE = "STUDENT_SESSION";

    // Secure by default; local http dev sets APP_COOKIE_SECURE=false, else the browser drops the
    // cookie over http and login silently fails.
    @Value("${app.cookie.secure:true}")
    private boolean secure;

    // Initialised as well as @Value-injected: direct `new SessionCookieService()` (unit tests) would
    // otherwise leave this null, and ResponseCookie omits the attribute entirely for a null value —
    // dropping SameSite silently rather than failing.
    @Value("${app.cookie.same-site:Lax}")
    private String sameSite = "Lax";

    // Lower-cased key -> canonical spelling. Browsers compare the value case-insensitively, so this
    // accepts any casing and normalises, rather than failing a boot over "lax".
    private static final Map<String, String> LEGAL_SAME_SITE =
            Map.of("lax", "Lax", "strict", "Strict", "none", "None");

    /**
     * Fail fast on a misconfigured cookie policy, the way a short {@code app.jwt.secret} already
     * does. Every invalid value is otherwise SILENT: a blank makes ResponseCookie omit the attribute
     * entirely, a typo ships verbatim for the browser to disregard, and an arbitrary string injects
     * cookie attributes (a probe of {@code "a; Path=/evil"} overrode the real {@code Path=/}).
     * Each of those downgrades the cookie policy while the app boots green.
     */
    @PostConstruct
    void validateCookiePolicy() {
        String canonical = sameSite == null ? null
                : LEGAL_SAME_SITE.get(sameSite.trim().toLowerCase(Locale.ROOT));
        if (canonical == null) {
            throw new IllegalStateException(
                "app.cookie.same-site must be Lax, Strict or None (was: \"" + sameSite + "\")");
        }
        // The one combination browsers reject outright. Blocked here rather than left to be
        // discovered as "login works, every request after it is 401".
        if ("None".equals(canonical) && !secure) {
            throw new IllegalStateException(
                "app.cookie.same-site=None requires app.cookie.secure=true; browsers drop a "
                + "SameSite=None cookie sent without Secure.");
        }
        this.sameSite = canonical;
    }

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
                .sameSite(sameSite)
                .path("/");
    }
}
