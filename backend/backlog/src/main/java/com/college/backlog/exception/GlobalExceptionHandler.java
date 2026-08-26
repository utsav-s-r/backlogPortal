package com.college.backlog.exception;

import jakarta.validation.ConstraintViolationException;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.college.backlog.web.RequestBodyTooLargeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidationExceptions(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining(". "));
        return Map.of("message", errorMessage + ".");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("message", ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Map<String, String> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return Map.of("message", ex.getMessage());
    }

    // ---- client errors that used to reach the catch-all as 500s ----
    // ExceptionHandlerExceptionResolver runs BEFORE DefaultHandlerExceptionResolver, so the
    // Exception.class handler below was intercepting Spring MVC's own exceptions and reporting
    // ordinary client mistakes as server faults — each one also logged at ERROR with a stack
    // trace, which buried real 500s. Handler ORDER in this file is irrelevant: @RestControllerAdvice
    // picks the most specific handler, and the catch-all only wins when nothing else matches.
    // Messages stay generic: the exception text carries class names, field paths and parse offsets.

    /** A body that outran the size cap mid-read (chunked, so RequestSizeLimitFilter could not
     *  reject it on Content-Length). 413, not 400 — the submission is not malformed, it is too big,
     *  and telling the client "not valid JSON" would send them debugging the wrong thing. */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, String> handleBodyTooLarge(RequestBodyTooLargeException ex) {
        logger.debug("Request body over the cap: {}", ex.getMessage());
        return Map.of("message", "Request body is too large.");
    }

    /** Malformed JSON, or a JSON value of the wrong type for its field. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // Jackson wraps whatever the stream threw, so an over-limit body arrives here disguised as
        // malformed JSON. Unwrap before deciding — without this the cap answers 400 and reads as a
        // client formatting bug.
        for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
            if (t instanceof RequestBodyTooLargeException) {
                logger.debug("Request body over the cap: {}", t.getMessage());
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(Map.of("message", "Request body is too large."));
            }
        }
        logger.debug("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("message", "Request body is missing or not valid JSON."));
    }

    /** A path variable or query parameter that won't convert (e.g. ?page=abc). Naming the
     *  parameter is safe — it is the client's own input, not an internal. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return Map.of("message", "Invalid value for '" + ex.getName() + "'.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMissingParameter(MissingServletRequestParameterException ex) {
        return Map.of("message", "Required parameter '" + ex.getParameterName() + "' is missing.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Map<String, String> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return Map.of("message", "Content-Type is not supported. Use application/json.");
    }

    /** Unmatched route. Without this an unknown URL answers 500 "the server is broken". */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNoResourceFound(NoResourceFoundException ex) {
        return Map.of("message", "No such endpoint.");
    }

    /** @Validated on request params/path variables (as opposed to @Valid on a body, which is
     *  MethodArgumentNotValidException above). */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining(". "));
        return Map.of("message", message.isBlank() ? "Invalid request parameters." : message);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleAccessDenied(AccessDeniedException ex) {
        return Map.of("message", "Access denied.");
    }

    // Fallback for DB-constraint violations no endpoint caught locally — a duplicate
    // (course_code, academic_year_offered) on subject create, a duplicate exam-cycle name.
    // Endpoints with a specific message still catch it first; the rest become 409, not 500.
    // The raw exception is logged, never returned — constraint names and SQL don't belong in
    // API responses.
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        logger.warn("Data integrity violation: {}", ex.getMessage());
        return Map.of("message",
            "This change conflicts with existing data (for example, a duplicate value). Check the values and try again.");
    }

    // The client hung up mid-response (browser refresh, navigate away, cancelled download). NOT an
    // application error, so it must not reach the catch-all below: that logged every closed tab at
    // ERROR with a full stack trace, and then failed itself with "No converter for ... with preset
    // Content-Type 'image/png'" — by the time bytes are streaming (a static asset, or a PDF), the
    // response is committed with a binary content type and a JSON body can no longer be written.
    // Returning void writes nothing, which is right: there is no one left to write to.
    // Spring dispatches to the most specific handler by exception type, so this wins over
    // Exception.class regardless of declaration order.
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        logger.debug("Client disconnected before the response finished: {}", ex.getMessage());
    }

    // Last resort: log in full, return nothing specific. Deliberately NOT extended to
    // IllegalArgumentException — the domain validators (Semesters, AcademicYears) throw it and
    // every caller already wraps it as a 400, but NumberFormatException extends it too, so a
    // blanket 400 would relabel genuine server bugs as client errors and hide them from ERROR.
    // That is this handler's original sin in mirror image. Keep wrapping IAE at the call site.
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleAllOtherExceptions(Exception ex) {
        logger.error("An unexpected error occurred: {}", ex.getMessage(), ex);
        return Map.of("message", "An unexpected internal error occurred. Please try again later or contact support.");
    }
}