package com.college.backlog.web;

import java.io.IOException;

/**
 * Thrown by {@link RequestSizeLimitFilter}'s counting stream when a request body exceeds the
 * configured cap mid-read — i.e. the chunked case, where Content-Length was absent and the filter
 * could not reject up front.
 *
 * <p>An {@code IOException} on purpose: it is thrown from inside {@code ServletInputStream.read},
 * where only an IOException is legal. Jackson wraps it in {@code HttpMessageNotReadableException},
 * so {@code GlobalExceptionHandler} unwraps the cause chain to answer 413 rather than the 400 that
 * shape would otherwise produce.
 */
public class RequestBodyTooLargeException extends IOException {
    public RequestBodyTooLargeException(long limitBytes) {
        super("Request body exceeds the " + limitBytes + " byte limit.");
    }
}
