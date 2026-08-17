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