package com.college.backlog.exception;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

        // `throws` because ClientAbortException extends IOException and so is CHECKED — the same
        // reason the real one surfaces from streaming writes rather than from handler logic.
        @GetMapping("/aborted")
        String aborted() throws ClientAbortException {
            throw new ClientAbortException(new IOException("Broken pipe"));
        }

        // The chain a real violation arrives in, confirmed against Postgres 18: Spring's
        // DataIntegrityViolationException wraps Hibernate's ConstraintViolationException wraps
        // the driver's SQLException, and only the innermost one carries the SQLSTATE.
        @GetMapping("/violation")
        String violation(@RequestParam String sqlState) {
            SQLException driver = new SQLException(
                "ERROR: violates constraint \"uq_probe_secret_constraint\"", sqlState);
            throw new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException(
                    "could not execute statement", driver, "uq_probe_secret_constraint"));
        }

        /** Hibernate refusing before any statement runs: no SQLException anywhere in the chain. */
        @GetMapping("/violation-no-state")
        String violationWithoutSqlState() {
            throw new DataIntegrityViolationException("not-null property references a null value");
        }
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

    /**
     * A client hanging up mid-response is NOT a server error. Observed live on 2026-08-24: a
     * cancelled PNG request reached the catch-all, logged ERROR with a 130-line stack trace, and
     * then the handler itself failed — "No converter for ... with preset Content-Type 'image/png'"
     * — because a committed binary response cannot carry a JSON body. Asserting no 500 and an
     * EMPTY body is what pins both halves: not misreported as a server error, and nothing written.
     */
    @Test
    void aClientDisconnectIsNotReportedAsAServerError() throws Exception {
        mvc.perform(get("/probe/aborted"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ---- DB constraint violations: only a duplicate is the caller's to fix ----

    /**
     * 23505, unique_violation. The one integrity failure a caller can act on, and the only one
     * that stays a 409 — a duplicate course code, exam-cycle name or department code.
     */
    @Test
    void aUniqueViolationIs409AndSaysSo() throws Exception {
        mvc.perform(get("/probe/violation").param("sqlState", "23505"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                    org.hamcrest.Matchers.containsString("duplicate value")));
    }

    /**
     * 23514, check_violation. Every CHECK in this schema mirrors a rule the application validates
     * first, so reaching the database with data that breaks one means a write path skipped its
     * validation. Telling the caller to "check the values" hides that in a WARN.
     */
    @Test
    void aCheckViolationIs500NotAMisleadingDuplicate() throws Exception {
        mvc.perform(get("/probe/violation").param("sqlState", "23514"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("duplicate"))));
    }

    /** 23503, foreign_key_violation — every referenced-row refusal users can legitimately cause
     *  is already caught locally with its own message, so one reaching here is a server bug. */
    @Test
    void aForeignKeyViolationIs500() throws Exception {
        mvc.perform(get("/probe/violation").param("sqlState", "23503"))
                .andExpect(status().isInternalServerError());
    }

    /** 23502, not_null_violation. */
    @Test
    void aNotNullViolationIs500() throws Exception {
        mvc.perform(get("/probe/violation").param("sqlState", "23502"))
                .andExpect(status().isInternalServerError());
    }

    /** Nothing reached the database, so there is no duplicate to report. Unknown is unexpected. */
    @Test
    void aViolationCarryingNoSqlStateIs500() throws Exception {
        mvc.perform(get("/probe/violation-no-state"))
                .andExpect(status().isInternalServerError());
    }

    /** Constraint names and SQL are internal, on BOTH branches. */
    @Test
    void neitherBranchLeaksTheConstraintName() throws Exception {
        mvc.perform(get("/probe/violation").param("sqlState", "23505"))
                .andExpect(content().string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uq_probe_secret_constraint"))));
        mvc.perform(get("/probe/violation").param("sqlState", "23514"))
                .andExpect(content().string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uq_probe_secret_constraint"))));
    }
}
