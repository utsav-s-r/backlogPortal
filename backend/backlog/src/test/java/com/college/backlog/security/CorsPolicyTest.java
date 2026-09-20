package com.college.backlog.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CORS preflight must admit every verb the SPA sends. Inert on the same-origin deployment, so
 * a missing verb only shows on a cross-origin one — as a single failing feature (PATCH = the staff
 * rename), not a broken app. Full context: CORS runs in the filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsPolicyTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void preflightAdmitsEveryVerbTheSpaSends(String method) throws Exception {
        mockMvc.perform(options("/api/admin/users/someone")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", method))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString(method)));
    }

    /** Negative control: without it the cases above would pass on a permit-everything policy. */
    @Test
    void preflightFromAnUnlistedOriginIsRefused() throws Exception {
        mockMvc.perform(options("/api/admin/users/someone")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }
}
