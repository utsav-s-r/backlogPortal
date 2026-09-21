package com.college.backlog.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
     * HEAD is GET without a body, and uptime monitors default to it. The public-route predicate
     * used to test {@code "GET".equals(method)} alone, so {@code HEAD /} answered 401 while
     * {@code GET /} answered 200 — measured on production 2026-08-26.
     *
     * <p>The second half is the one that must never regress: widening the predicate to HEAD must
     * NOT widen the API surface. {@code !API_PATHS} still excludes {@code /api/**}, so an
     * unauthenticated HEAD there stays denied. Assert both directions — the permit alone would pass
     * just as well if the rule had been widened to everything.
     */
    @Test
    void permitsHeadWhereverItPermitsGetButNotOnTheApi() throws Exception {
        // Asserted as "HEAD matches GET" rather than a literal 200: the SPA shell only reaches
        // classpath:/static/ at image-build time, so in tests BOTH are 404 (the resource resolver,
        // not security) while in production both are 200. A hardcoded 200 would fail here for a
        // reason that has nothing to do with the rule under test.
        int getStatus = mockMvc.perform(get("/")).andReturn().getResponse().getStatus();
        int headStatus = mockMvc.perform(head("/")).andReturn().getResponse().getStatus();
        assertThat(headStatus).isEqualTo(getStatus);
        assertThat(headStatus).isNotEqualTo(401); // the bug: HEAD fell through to authenticated()

        // The half that must never regress — widening to HEAD must not widen the API surface.
        //
        // Probed at a path with NO handler, deliberately. An existing endpoint like
        // /api/admin/users has @PreAuthorize behind it, which answers 401 for an anonymous caller
        // whether or not this rule permitted the request — so asserting there passes even with the
        // !API_PATHS half deleted. Verified by mutation 2026-08-26: that version could not fail.
        // With no handler there is no second layer: 401 means the request was DENIED here, while a
        // fail-open would let it through to 404. Same shape as the /%61pi/nonexistent bypass
        // recorded in SecurityConfig's comment.
        mockMvc.perform(head("/api/nonexistent")).andExpect(status().isUnauthorized());
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
