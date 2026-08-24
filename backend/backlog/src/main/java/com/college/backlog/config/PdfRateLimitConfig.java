package com.college.backlog.config;

import com.college.backlog.service.PdfRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

/**
 * Applies {@link PdfRateLimitService} to the two PDF endpoints, and nowhere else.
 *
 * <p>A HandlerInterceptor rather than a servlet filter, deliberately: interceptors run inside
 * DispatcherServlet, AFTER the security filter chain, so the authenticated principal is already
 * populated and can be used as the rate-limit key. A filter would have to run before authentication
 * and be left keying on something attacker-controlled.
 */
@Configuration
public class PdfRateLimitConfig implements WebMvcConfigurer {

    /** Exactly the two iText paths. Anything else is I/O-bound and not worth a Redis round trip.
     *  Login is ABSENT on purpose and must stay absent — no login path may ever return 429
     *  (docs/adr/student-authentication.md). */
    private static final String ADMIN_EXPORT_PATH = "/api/admin/export-pdf";
    private static final String STUDENT_PDF_PATH = "/api/student/registrations/*/pdf";

    private final PdfRateLimitService rateLimitService;

    public PdfRateLimitConfig(PdfRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) throws Exception {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String principal = (auth != null) ? auth.getName() : null;

                if (rateLimitService.tryConsume(principal)) {
                    return true;
                }

                // 429 with Retry-After. Written directly rather than thrown: filter/interceptor
                // level failures never reach GlobalExceptionHandler, and sendError() would produce
                // no message body under server.error.include-message=never.
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HttpHeaders.RETRY_AFTER,
                        String.valueOf(rateLimitService.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(
                        "{\"message\":\"Too many PDF downloads. Please try again later.\"}");
                return false;
            }
        }).addPathPatterns(ADMIN_EXPORT_PATH, STUDENT_PDF_PATH);
    }
}
