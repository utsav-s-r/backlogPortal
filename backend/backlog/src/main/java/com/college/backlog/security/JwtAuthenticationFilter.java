package com.college.backlog.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SessionCookieService sessionCookieService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // The JWT travels in an httpOnly cookie, not the Authorization header, so page scripts
        // can't read it. Pick the cookie matching the request's audience (student vs admin) so a
        // dual session in one browser resolves to the right identity.
        final String jwt = sessionCookieService.read(
                request, sessionCookieService.cookieNameForPath(request.getRequestURI()));
        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Parsing verifies the signature and throws on an expired/tampered/malformed token. In a
        // servlet filter that escapes the DispatcherServlet as a 500 instead of "unauthenticated",
        // stranding clients whose token merely expired — so treat any parse failure as anonymous
        // and continue the chain, letting the authorization rules produce a clean 401/403.
        String username;
        try {
            username = jwtService.getUsernameFromToken(jwt);
        } catch (Exception e) {
            // DEBUG, not WARN: expired tokens are normal traffic and would flood the log. But
            // logging nothing at all made a botched JWT-secret rotation indistinguishable from
            // "everyone's session expired at once" — with no record either way.
            log.debug("Rejected token ({}), continuing as anonymous", e.getClass().getSimpleName());
            filterChain.doFilter(request, response);
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            if (jwtService.validateToken(jwt)) {
                String role = jwtService.getRoleFromToken(jwt);
                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

                // A token with no `iat` cannot be aged against the account's session_valid_from,
                // so it is treated as unauthenticated rather than trusted: generateToken always
                // sets one, so the only way here is a token this application did not mint.
                Instant issuedAt = jwtService.getIssuedAtFromToken(jwt);
                if (issuedAt == null) {
                    log.debug("Rejected token with no issued-at claim, continuing as anonymous");
                    filterChain.doFilter(request, response);
                    return;
                }

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(username, null, authorities);
                // Carries the issue time to AccountExistenceFilter, which owns revocation — see
                // JwtSessionDetails. Replaces the plain WebAuthenticationDetails it extends;
                // nothing else in the app reads getDetails().
                authToken.setDetails(new JwtSessionDetails(request, issuedAt));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }
}