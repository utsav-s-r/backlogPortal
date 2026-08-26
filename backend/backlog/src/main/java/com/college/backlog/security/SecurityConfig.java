package com.college.backlog.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Every path under /api, matched with the SAME PathPattern engine Spring MVC dispatches with.
     *  Used only by the static-asset permitAll rule below; see the comment there for why a raw
     *  getRequestURI() prefix check is not a valid substitute. */
    private static final RequestMatcher API_PATHS =
            PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private AccountExistenceFilter accountExistenceFilter;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        // The JWT rides in a browser-sent cookie, so CSRF is a live threat and is enabled:
        // double-submit via a readable XSRF-TOKEN cookie the SPA echoes as X-XSRF-TOKEN. Plain
        // (non-Xor) handler so the header matches the cookie verbatim, which is what axios sends.
        // Login is exempt (no token before authenticating); the cookie is issued on its response,
        // and every other mutation is protected.
        // The token must stay STABLE across requests — see StableCsrfTokenRepository: stateless
        // per-request JWT auth otherwise has CsrfAuthenticationStrategy delete the cookie each
        // time, breaking back-to-back mutations (403 -> logout).
        StableCsrfTokenRepository csrfTokenRepository =
                new StableCsrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/login", "/api/student/auth/login",
                                "/api/auth/logout", "/api/student/auth/logout"))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Spring Security's defaults give nosniff + X-Frame-Options: DENY and NOTHING else —
                // no CSP (Spring sets no default), no Referrer-Policy, no Permissions-Policy.
                // Measured 2026-08-26 against the packaged SPA, before and after.
                //
                // Every directive below is derived from what this app actually does; do not relax one
                // without re-checking that:
                //  - script-src 'self' with NO 'unsafe-inline': the Vite build emits zero inline
                //    <script> (checked in the built index.html) and there is no
                //    dangerouslySetInnerHTML anywhere. Adding 'unsafe-inline' here would forfeit the
                //    main thing this policy buys.
                //  - style-src NEEDS 'unsafe-inline': ~12 components use inline style={{}} (the hero
                //    especially — index.css documents why), and React renders those as style
                //    attributes. Removing it silently unstyles the homepage.
                //  - fonts.googleapis.com (the @import in index.css) serves the STYLESHEET, so it
                //    belongs in style-src; fonts.gstatic.com serves the font FILES it references, so
                //    it belongs in font-src. Both are needed — one without the other loses the faces.
                //  - frame-ancestors 'none' restates X-Frame-Options: DENY for browsers that prefer CSP.
                // PDFs download through a blob: URL (lib/download.js). Auxiliary browsing contexts
                // opened with window.open are not governed by frame-src/child-src, so no blob: source
                // is needed here — verified in-browser, not assumed.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                "default-src 'self'",
                                "script-src 'self'",
                                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
                                "font-src 'self' https://fonts.gstatic.com",
                                "img-src 'self'",
                                "connect-src 'self'",
                                "object-src 'none'",
                                "base-uri 'self'",
                                "form-action 'self'",
                                "frame-ancestors 'none'")))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // The app asks for none of these; deny them so a future dependency cannot.
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        // Spring emits HSTS only when it CONSIDERS the request secure, which behind a
                        // TLS-terminating proxy requires server.forward-headers-strategy (set in
                        // application.properties) — without that this block is inert in production.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Spring's default entry point is Http403ForbiddenEntryPoint, which would answer an
                // expired/absent token with 403 — indistinguishable from a real scope denial, and
                // bodyless under server.error.include-message=never. Split them so the SPA can act:
                // 401 = no valid session, sign out; 403 = authenticated but denied, show and stay put.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeJsonMessage(
                                res, HttpServletResponse.SC_UNAUTHORIZED,
                                "Session expired. Please sign in again."))
                        // CsrfFilter reuses this handler. A CSRF failure is a broken double-submit
                        // pair, not a permissions problem, and re-login is the only fix the user
                        // has — so answer 401 to sign them out, and reserve 403 for real denials.
                        .accessDeniedHandler((req, res, e) -> {
                            if (e instanceof CsrfException) {
                                writeJsonMessage(res, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Session expired. Please sign in again.");
                            } else {
                                writeJsonMessage(res, HttpServletResponse.SC_FORBIDDEN, "Access denied.");
                            }
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(accountExistenceFilter, JwtAuthenticationFilter.class)
                // forces the CsrfToken to render so CookieCsrfTokenRepository writes the cookie
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/student/auth/login").permitAll()
                        // logout only clears the cookie — allowed even with a lapsed session
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/student/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/departments").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/registration-status").permitAll()
                        // PRINCIPAL deliberately absent: verification is not theirs. In step with
                        // RegistrationController#verifyRegistration's @PreAuthorize and the
                        // dashboard gate; listing it here was dead (both must pass) but made
                        // removing that annotation silently GRANT the role. Binds both ways, and
                        // no HttpMethod arg = the whole verify/ subtree: a new endpoint under it
                        // must join this list or its own @PreAuthorize is silently overridden.
                        // See docs/adr/auth-scoping.md.
                        .requestMatchers("/api/register/verify/**").hasAnyRole("ADMIN", "HOD", "DEPT_OFFICE", "PROCTOR")
                        .requestMatchers(HttpMethod.POST, "/api/register").hasRole("STUDENT")
                        .requestMatchers("/api/student/**").hasRole("STUDENT")
                        // PROCTOR clears this coarse gate; each /api/admin controller's own
                        // @PreAuthorize decides if proctors may use it (default: no)
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "PRINCIPAL", "HOD", "DEPT_OFFICE", "PROCTOR")
                        // The SPA shell and its assets, served from this same jar (see
                        // SpaStaticResourceConfig). Without this they hit anyRequest().authenticated()
                        // below and 401 — the login page itself could never load.
                        //
                        // A PREDICATE, not a path list, because React Router owns arbitrary
                        // client-side routes (/admin/students, /student/login, ...) and any
                        // hardcoded list breaks silently the next time one is added.
                        //
                        // Cannot widen the API surface, by construction: it is scoped to GET AND to
                        // paths outside /api/, so every /api request still falls through to the
                        // rules above and then to anyRequest().authenticated(). A new API endpoint
                        // that forgets its rule stays DENIED, not opened. Keep both halves of that
                        // condition — dropping either makes this a fail-open rule.
                        //
                        // The path test MUST go through API_PATHS, never request.getRequestURI().
                        // getRequestURI() is the RAW, undecoded URI, while Spring MVC dispatches on
                        // the decoded, normalised path — so a raw prefix check and the router
                        // disagree about what the path is. Demonstrated 2026-08-18: with a
                        // getRequestURI() check, GET /%61pi/nonexistent was PERMITTED here (the raw
                        // string does not start with "/api/") and then routed as /api/nonexistent.
                        // Only @PreAuthorize stopped it reaching admin data. PathPatternRequestMatcher
                        // uses the same PathPattern engine as the dispatcher, so the two agree.
                        //
                        // HEAD rides along with GET: it is GET without a response body, and Spring
                        // MVC answers it from the same handler. Without it `HEAD /` fell through to
                        // anyRequest().authenticated() and answered 401 while `GET /` answered 200
                        // — harmless for browsers and the keep-alive cron (both send GET), but an
                        // uptime monitor defaults to HEAD and would report the site permanently
                        // down. Measured on production 2026-08-26.
                        // This does NOT widen the API surface: the !API_PATHS half is untouched, so
                        // HEAD /api/** still falls through to authenticated() exactly as before.
                        .requestMatchers(request -> ("GET".equals(request.getMethod())
                                    || "HEAD".equals(request.getMethod()))
                                && !API_PATHS.matches(request)).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /** Write a {@code {"message": ...}} body directly: filter-level failures never reach
     *  GlobalExceptionHandler, and sendError() would yield no message at all. Callers pass
     *  literals, so no JSON escaping is needed. */
    private static void writeJsonMessage(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}