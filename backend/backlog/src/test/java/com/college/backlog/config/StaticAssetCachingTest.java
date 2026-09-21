package com.college.backlog.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the cache policy for the packaged SPA: hashed chunks cacheable forever, everything else
 * not cacheable at all.
 *
 * <p>The bug this exists for: Spring Security's CacheControlHeadersWriter stamps
 * {@code no-cache, no-store, max-age=0, must-revalidate} on every response, and nothing opted the
 * static assets out. Because the app is route-split, {@code no-store} meant each client-side
 * navigation re-fetched its chunk and nothing survived a browser restart — on a phone the tap sat
 * on a blank Suspense fallback long enough that Login and Back-to-home read as dead buttons, and
 * the same thing recurred on every cold start.
 *
 * <p>Full context on purpose: the competing header comes from the security filter chain, so a
 * standalone MockMvc would never see it and every assertion here would pass vacuously — the same
 * trap SecurityHeadersTest documents.
 *
 * <p>Assertions are per-property (max-age, immutable, absence of no-store) rather than a
 * value-for-value match on the whole header: an exact-string assertion just gets "fixed" by pasting
 * in whatever the code now emits, which proves nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticAssetCachingTest {

    /** Resolved from src/test/resources/static/assets/. ./mvnw test bundles no frontend, so the
     *  fixture is what makes these assertions land on a 200 instead of a 404. */
    private static final String HASHED_CHUNK = "/assets/chunk-TESTHASH.js";

    /** Public, so no seeded user is needed and AccountExistenceFilter is not in play. */
    private static final String PUBLIC_ENDPOINT = "/api/registration-status";

    @Autowired
    private MockMvc mockMvc;

    /** A year plus {@code immutable}: the content hash IS the cache key, so a cached copy can never
     *  be stale, and {@code immutable} additionally suppresses revalidation on reload. */
    @Test
    void hashedAssetsAreCacheableForAYearAndImmutable() throws Exception {
        mockMvc.perform(get(HASHED_CHUNK))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    /**
     * THE regression. Security's writer skips a Cache-Control that the handler already set, so the
     * whole fix rests on the handler winning — assert the no-store is gone, not merely that a
     * max-age is present, because both headers cannot coexist and only this direction proves which
     * one shipped.
     */
    @Test
    void hashedAssetsDoNotCarrySpringSecurityNoStore() throws Exception {
        mockMvc.perform(get(HASHED_CHUNK))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", not(containsString("no-store"))));
    }

    /**
     * The other half, and the one that would make this change dangerous if it regressed: the
     * unhashed shell is what POINTS AT the current hashes, so caching it would serve a stale
     * document asking for chunks that no longer exist. Probed through the API, which shares the
     * default — the SPA shell only reaches classpath:/static/ at image-build time.
     */
    @Test
    void everythingElseKeepsNoStore() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    /**
     * A missing chunk must 404, never fall through to the SPA shell. After a deploy a cached shell
     * asks for a filename that is gone; answering 200 text/html means the browser tries to parse
     * HTML as JavaScript and the failure surfaces as a syntax error instead of a load error the
     * ErrorBoundary can act on. Non-vacuous only because the index.html fixture makes the fallback
     * live — without it this path would 404 for want of any file at all.
     */
    @Test
    void aMissingHashedAssetIs404NotTheSpaShell() throws Exception {
        mockMvc.perform(get("/assets/chunk-GONE.js"))
                .andExpect(status().isNotFound());

        // Control: the fallback really is live, so the 404 above is the /assets rule and not an
        // empty classpath.
        mockMvc.perform(get("/some/client/side/route"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"root\">")));
    }
}
