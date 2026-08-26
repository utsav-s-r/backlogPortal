package com.college.backlog.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Bounds how large a request body may be. Nothing did before this (§15): container defaults
 * applied, so a single large POST could exhaust heap on the 512 MB free-tier instance.
 *
 * <p>Two layers, because one is not enough:
 * <ol>
 *   <li><b>Content-Length</b> — rejected before the body is read at all, which is the whole point:
 *       no bytes are buffered. Covers every real client (axios, browsers and curl all send it).</li>
 *   <li><b>A counting stream</b> — a request may legally omit Content-Length by using chunked
 *       transfer-encoding, which would walk straight past layer 1. The wrapper below stops the read
 *       at the limit instead of letting it run to heap exhaustion.</li>
 * </ol>
 *
 * <p>Runs at HIGHEST_PRECEDENCE, ahead of the security chain: an oversized body should cost nothing
 * to refuse, and there is no reason to authenticate it first.
 *
 * <p><b>Sizing.</b> The default is deliberately generous, not tight — the bulk student import POSTs
 * the whole parsed CSV as one JSON array, and a cap that breaks a legitimate import is worse than
 * no cap. ~120 bytes per row means 8 MB still carries tens of thousands of students. Tune with
 * {@code APP_MAX_REQUEST_BYTES} rather than editing this.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Methods that carry a body. GET/HEAD/DELETE/OPTIONS skip the wrapper entirely. */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${app.request.max-body-bytes:8388608}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!BODY_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        // Layer 1: refuse up front, without reading a byte.
        if (request.getContentLengthLong() > maxBytes) {
            reject(response);
            return;
        }
        // Layer 2: chunked bodies report -1 above, so cap them as they are read.
        chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
    }

    /** Written directly rather than thrown: this runs before the security chain and before
     *  @RestControllerAdvice, so neither would format it. Matches the {"message": ...} shape every
     *  other failure uses. The literal needs no JSON escaping. */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"Request body is too large.\"}");
    }

    /** Wraps the body so a chunked request cannot outrun the limit. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                private int track(int bytesRead) throws IOException {
                    if (bytesRead > 0) {
                        count += bytesRead;
                        if (count > limit) throw new RequestBodyTooLargeException(limit);
                    }
                    return bytesRead;
                }

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    track(b == -1 ? -1 : 1);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return track(delegate.read(b, off, len));
                }

                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener l) { delegate.setReadListener(l); }
            };
        }
    }
}
