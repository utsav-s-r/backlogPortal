package com.college.backlog.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the request-size cap (§15). Nothing bounded a request body before it, so one large POST
 * could exhaust heap on the free-tier instance.
 *
 * <p>The cap is overridden to a tiny value here rather than posting 8 MB in a test: the property is
 * the thing under test, and building a real 8 MB body would make the suite slow for no extra proof.
 *
 * <p>Uses a PUBLIC unauthenticated path on purpose. The filter runs at HIGHEST_PRECEDENCE, ahead of
 * the security chain — asserting on an authenticated endpoint would not distinguish "rejected for
 * size" from "rejected for no session", which is the vacuous-assertion trap this suite avoids
 * elsewhere too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.request.max-body-bytes=100")
class RequestSizeLimitFilterTest {

    /** Public, permitAll, and 100 bytes is far below anything legitimate. */
    private static final String PUBLIC_POST = "/api/student/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestSizeLimitFilter filter;

    @Test
    void rejectsABodyOverTheCapWith413() throws Exception {
        String oversized = "{\"rollNo\":\"" + "A".repeat(300) + "\"}";

        mockMvc.perform(post(PUBLIC_POST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }

    /**
     * The other half: the cap must not turn into a blanket refusal. A body under the limit has to
     * reach the handler — proven by getting anything OTHER than 413 back (the credentials are
     * nonsense, so 400/401 is the expected outcome and is fine).
     */
    @Test
    void letsABodyUnderTheCapThrough() throws Exception {
        mockMvc.perform(post(PUBLIC_POST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNo\":\"1MS22CS001\",\"dateOfBirth\":\"2004-05-01\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 413) {
                        throw new AssertionError("a legitimate body was refused as too large");
                    }
                });
    }

    /** GET carries no body, so the filter must not wrap or refuse it whatever the cap says. */
    @Test
    void ignoresBodylessMethods() throws Exception {
        mockMvc.perform(get("/api/registration-status"))
                .andExpect(status().isOk());
    }

    /**
     * Isolates the CHUNKED path — the counting stream — which the test above cannot.
     *
     * <p>Mutation-tested 2026-08-26: removing either layer on its own left the 413 test above green,
     * because the surviving layer still caught it. Mutual redundancy is the right behaviour but it
     * makes a status assertion blind to which half did the work. A chunked request reports no
     * Content-Length, so layer 1 is bypassed by construction and only the wrapper can refuse it —
     * modelled here by a request whose {@code getContentLengthLong()} returns -1 while it still
     * carries a body.
     */
    @Test
    void theCountingStreamCatchesABodyWithNoContentLength() throws Exception {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", PUBLIC_POST) {
            @Override
            public long getContentLengthLong() {
                return -1; // what chunked transfer-encoding reports
            }
        };
        chunked.setContentType(MediaType.APPLICATION_JSON_VALUE);
        chunked.setContent(("{\"rollNo\":\"" + "A".repeat(300) + "\"}").getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        // Read the wrapped body the way a message converter would; the wrapper must stop it.
        assertThatThrownBy(() -> filter.doFilter(chunked, response, (req, res) ->
                ((HttpServletRequest) req).getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }
}
