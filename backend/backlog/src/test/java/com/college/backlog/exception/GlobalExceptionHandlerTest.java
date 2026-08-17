package com.college.backlog.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error-status contract. Before these mappings existed, every case below reached the catch-all
 * {@code @ExceptionHandler(Exception.class)} and answered **500** — because
 * ExceptionHandlerExceptionResolver runs before DefaultHandlerExceptionResolver, so the catch-all
 * intercepted Spring MVC's own exceptions. Each also logged ERROR with a stack trace, which buried
 * real 500s under client typos.
 *
 * Standalone MockMvc, so no Spring context and no database — the advice plus one throwaway
 * controller. (NoResourceFoundException → 404 is deliberately absent: unmatched-route handling is a
 * property of the real dispatcher, not of standalone setup, so it is covered by the live probe.)
 */
class GlobalExceptionHandlerTest {

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {
        record Body(int semester) {}

        @PostMapping("/body")
        String body(@RequestBody Body body) { return "ok"; }

        @GetMapping("/typed")
        String typed(@RequestParam Long id) { return "ok"; }

        @GetMapping("/conflict")
        String conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT, "Already actioned."); }

        @GetMapping("/missing")
        String missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subject not found with ID: 9"); }

        @GetMapping("/boom")
        String boom() { throw new IllegalStateException("internal detail that must not leak"); }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void malformedJsonIs400NotServerError() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"semester\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or not valid JSON."));
    }

    @Test
    void aJsonValueOfTheWrongTypeIs400() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"semester\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMissingBodyIs400() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Content-Type is not supported. Use application/json."));
    }

    /** The parameter name is the client's own input, so naming it is safe and actionable. */
    @Test
    void aQueryParamOfTheWrongTypeIs400AndNamesTheParam() throws Exception {
        mvc.perform(get("/probe/typed").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id'."));
    }

    @Test
    void aMissingRequiredParamIs400AndNamesIt() throws Exception {
        mvc.perform(get("/probe/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'id' is missing."));
    }

    @Test
    void responseStatusExceptionKeepsItsOwnStatusAndReason() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already actioned."));
    }

    /** 404s all come through ResponseStatusException now — ResourceNotFoundException was deleted. */
    @Test
    void aNotFoundResponseStatusExceptionIs404() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Subject not found with ID: 9"));
    }

    /** The catch-all still exists and still hides internals — that part was always correct. */
    @Test
    void anUnexpectedExceptionIsStill500AndLeaksNothing() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("An unexpected internal error occurred. Please try again later or contact support."));
    }
}
