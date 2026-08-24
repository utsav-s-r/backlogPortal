package com.college.backlog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the built React SPA from the same jar as the API, so the browser sees ONE origin.
 *
 * <p>That is not a packaging preference. {@code frontend/src/lib/api.js} hardcodes
 * {@code baseURL: "/api"}, and the session JWT rides in a {@code SameSite=Lax} cookie
 * ({@code SessionCookieService}) which browsers do NOT send on cross-site XHR. Split the SPA and
 * the API across two origins and login succeeds while every request after it arrives anonymous.
 * One origin removes that whole class of failure instead of managing it.
 *
 * <p>The frontend build lands in {@code classpath:/static/} at image build time (see the root
 * Dockerfile). When it is absent — a backend-only build, or {@code ./mvnw test} — every method
 * here falls through to a normal 404, so nothing depends on the assets existing.
 */
@Configuration
public class SpaStaticResourceConfig implements WebMvcConfigurer {

    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        // super, NOT a bare location.createRelative(): the superclass gates its
                        // return on checkResource(), which asserts the resolved file really is
                        // UNDER the configured location. That is this resolver's path-traversal
                        // containment check, and open-coding createRelative silently drops it —
                        // which matters more here because the companion permitAll rule in
                        // SecurityConfig makes every non-/api GET unauthenticated.
                        Resource requested = super.getResource(resourcePath, location);
                        if (requested != null) {
                            return requested;
                        }
                        // Load-bearing: without this, an unmatched /api/** path would fall through
                        // to the SPA shell and answer 200 text/html. A typo'd endpoint would look
                        // like a working page, and api.js would try to parse HTML as JSON.
                        // Controllers are matched before this resolver, so a REAL endpoint never
                        // reaches here — only ones that do not exist, which must stay 404.
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        // React Router owns the client-side routes (/admin/students,
                        // /student/login, ...). A hard refresh or pasted deep link asks the server
                        // for a file that was never built, so hand back the shell and let the
                        // router resolve it. Null when no frontend was bundled -> plain 404.
                        return INDEX.exists() ? INDEX : null;
                    }
                });
    }
}
