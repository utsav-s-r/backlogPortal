package com.college.backlog.security;

import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.time.Instant;

/**
 * The standard request details plus the instant the presented token was ISSUED, so
 * {@link AccountExistenceFilter} can refuse one older than the account's
 * {@code session_valid_from} without parsing the cookie a second time.
 *
 * <p>Only {@link JwtAuthenticationFilter} attaches this, and it is the only thing that
 * authenticates anyone in production ({@code SessionCreationPolicy.STATELESS}, no formLogin, no
 * httpBasic). A request whose details are NOT this type therefore did not come from a token —
 * in practice a {@code @WithMockUser} test — and the age check is skipped for it rather than
 * failing closed, which is what lets the 176 {@code @WithMockUser} authorization cases keep
 * testing what they were written to test. The revocation itself is proven with real tokens in
 * {@code SessionRevocationTest}.
 */
public class JwtSessionDetails extends WebAuthenticationDetails {

    private final Instant issuedAt;

    public JwtSessionDetails(jakarta.servlet.http.HttpServletRequest request, Instant issuedAt) {
        super(request);
        this.issuedAt = issuedAt;
    }

    /** When the presented token was minted. Never null — a token without an {@code iat} is
     *  rejected upstream rather than reaching here. */
    public Instant getIssuedAt() {
        return issuedAt;
    }
}
