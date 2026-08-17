package com.college.backlog.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    // 48-char secret, comfortably over the 32-byte HS256 floor.
    private static final String SECRET = "test-secret-that-is-definitely-long-enough-1234567890";

    private JwtService newService(String secret, long expirationMs) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "jwtSecret", secret);
        ReflectionTestUtils.setField(service, "jwtExpirationMs", expirationMs);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }

    @Test
    void roundTripsSubjectAndRole() {
        JwtService service = newService(SECRET, 3_600_000L);
        String token = service.generateToken("1MS22CS001", "STUDENT");

        assertThat(service.validateToken(token)).isTrue();
        assertThat(service.getUsernameFromToken(token)).isEqualTo("1MS22CS001");
        assertThat(service.getRoleFromToken(token)).isEqualTo("STUDENT");
    }

    @Test
    void expiredTokenFailsValidationInsteadOfThrowing() {
        // negative lifetime => the token is already expired the moment it is minted
        JwtService service = newService(SECRET, -1_000L);
        String token = service.generateToken("admin", "ADMIN");

        assertThat(service.validateToken(token)).isFalse();
    }

    @Test
    void tamperedTokenFailsValidation() {
        JwtService service = newService(SECRET, 3_600_000L);
        String token = service.generateToken("admin", "ADMIN");
        // Flip the payload's FIRST char — a full 6-bit base64url char, so the decoded bytes
        // always change and the signature fails. Flipping the signature's last char is
        // unreliable: its padding bits can decode to the same bytes, leaving the token valid.
        int payloadStart = token.indexOf('.') + 1;
        char c = token.charAt(payloadStart);
        String tampered = token.substring(0, payloadStart)
                + (c == 'A' ? 'B' : 'A')
                + token.substring(payloadStart + 1);

        assertThat(service.validateToken(tampered)).isFalse();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService issuer = newService(SECRET, 3_600_000L);
        JwtService other = newService("a-completely-different-secret-key-abcdefghijklmnop", 3_600_000L);
        String token = issuer.generateToken("admin", "ADMIN");

        assertThat(other.validateToken(token)).isFalse();
    }

    @Test
    void garbageTokenFailsValidation() {
        JwtService service = newService(SECRET, 3_600_000L);
        assertThat(service.validateToken("not-a-jwt")).isFalse();
    }

    @Test
    void tokenExpiresExactlyOneSessionLengthFromIssue() {
        // The session cap IS the token expiry — nothing can extend it, so this is the whole
        // "logged out after 1h" guarantee.
        JwtService service = newService(SECRET, 3_600_000L);
        long before = System.currentTimeMillis();
        String token = service.generateToken("admin", "ADMIN");
        long after = System.currentTimeMillis();

        // JWT exp is a NumericDate in SECONDS, so the minted millis are floored — hence the 1s
        // slack on the lower bound.
        Date expiry = ReflectionTestUtils.invokeMethod(service, "getExpirationDateFromToken", token);
        assertThat(expiry.getTime()).isBetween(before + 3_600_000L - 1_000L, after + 3_600_000L);
        // seconds-to-expiry is what the SPA schedules its sign-out from
        assertThat(service.secondsUntilExpiry(token)).isBetween(3_590L, 3_600L);
    }

    @Test
    void initFailsFastOnMissingOrWeakSecret() {
        assertThatThrownBy(() -> newService(null, 3_600_000L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newService("   ", 3_600_000L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newService("too-short", 3_600_000L))   // < 32 bytes
                .isInstanceOf(IllegalStateException.class);
    }
}
