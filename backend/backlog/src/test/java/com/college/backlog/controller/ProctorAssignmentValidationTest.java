package com.college.backlog.controller;

import com.college.backlog.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The empty-batch refusal moved from a hand-rolled check in the handler to {@code @NotEmpty} on
 * ProctorAssignRequest. That annotation is INERT without {@code @Valid} on the handler, and an inert
 * one turns an empty batch into a 200 no-op — a silent success that assigns nobody. These assert the
 * binding itself refuses, so removing either half fails the build.
 *
 * Standalone MockMvc, as in GlobalExceptionHandlerTest: no Spring context, no database. The
 * controller's {@code @Autowired} fields stay null ON PURPOSE — {@code req} is the first parameter,
 * so validation throws during argument resolution, before the handler body can touch a dependency.
 * Class-level {@code @PreAuthorize} is not applied here either; authorization is not what this
 * covers.
 */
class ProctorAssignmentValidationTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProctorAssignmentController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** Exactly the body the old ResponseStatusException produced — @NotEmpty carries no trailing
     *  period because GlobalExceptionHandler appends one. */
    @Test
    void anEmptyRollNoListIsRefusedAtBindingWith400() throws Exception {
        mvc.perform(post("/api/admin/proctor/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rollNos\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No students selected."));
    }

    /** @NotEmpty, not @NotNull: both spellings of "no students" must be refused identically. */
    @Test
    void anAbsentRollNoListIsRefusedTheSameWay() throws Exception {
        mvc.perform(post("/api/admin/proctor/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No students selected."));
    }
}
