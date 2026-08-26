package com.college.backlog.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the response-header policy added 2026-08-26. Before it, Spring Security's defaults gave
 * nosniff and X-Frame-Options and <b>nothing else</b> — no CSP (Spring ships no default), no
 * Referrer-Policy, no Permissions-Policy — measured against the packaged SPA.
 *
 * <p>Headers are the classic silent regression: nothing fails, no page breaks, and the loss is
 * invisible until someone curls production. Hence assertions on the specific properties that
 * matter, not on the whole header string — a value-for-value assertion would just get "fixed" by
 * pasting in whatever the code now emits, which proves nothing.
 *
 * <p>Full context on purpose: these headers come from the filter chain, so a standalone MockMvc
 * would pass vacuously (same reasoning as the authorization suites — see docs/adr/auth-scoping.md).
 * Uses the PUBLIC status endpoint, so no seeded user is needed and AccountExistenceFilter is not
 * in play.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersTest {

    private static final String PUBLIC_ENDPOINT = "/api/registration-status";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sendsAContentSecurityPolicy() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    /**
     * The load-bearing half of the policy. The app has zero inline scripts and no
     * dangerouslySetInnerHTML, so script-src can be strict — and if a future change relaxes it,
     * the policy stops buying anything and this fails instead of degrading quietly.
     */
    @Test
    void scriptSrcIsSelfOnlyAndNeverUnsafeInline() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy",
                        not(containsString("script-src 'self' 'unsafe-inline'"))));
    }

    /**
     * style-src MUST keep 'unsafe-inline': ~12 components render inline style={{}}, the homepage
     * hero among them. Asserted so a well-meaning tightening shows up here rather than as an
     * unstyled page nobody notices until a browser opens it.
     */
    @Test
    void styleAndFontSourcesAllowTheGoogleFontsImport() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("style-src 'self' 'unsafe-inline' https://fonts.googleapis.com")))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("font-src 'self' https://fonts.gstatic.com")));
    }

    @Test
    void sendsReferrerAndPermissionsPolicies() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("geolocation=()")));
    }

    /** Spring's defaults, restated so removing the headers block is caught in full. */
    @Test
    void keepsTheSpringDefaults() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    /**
     * HSTS is emitted only for a request Spring considers SECURE. In production that hinges on
     * server.forward-headers-strategy, without which the proxy's plain HTTP hop makes every request
     * look insecure and the header never ships — the actual state before 2026-08-26.
     */
    @Test
    void sendsHstsOnASecureRequestAndNotOnAPlainOne() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT).secure(true))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("includeSubDomains")));

        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
