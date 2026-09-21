package com.college.backlog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // THE session length. Sessions are a fixed, non-renewable window — there is no refresh, so
    // this expiry alone decides when everyone, student or admin, is signed out.
    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    private SecretKey signingKey;

    /**
     * Placeholder secrets that appear in TRACKED config templates, and are therefore public. Length
     * alone cannot catch these: {@code CHANGE_ME_TO_A_LONG_RANDOM_BASE64_SECRET} is 40 bytes, so it
     * sailed past the 32-byte floor below and an unset {@code JWT_SECRET} booted GREEN on a key
     * readable in the repo — anyone could mint {@code {sub:"admin", role:"ADMIN"}}, and
     * AccountExistenceFilter would pass it because the `admin` row exists.
     *
     * <p>This check, not the empty default in the templates, is what actually closes that hole.
     * {@code application.properties} is gitignored and generated ONCE (Dockerfile, or by hand from
     * the template), so every copy already on a disk or baked into an image keeps its old default
     * no matter what the template later says. Rejecting the VALUE catches those; rejecting only the
     * template would not.
     *
     * <p>Matched case-insensitively on the trimmed value. Add an entry here whenever a tracked
     * template gains a new placeholder — `openssl rand -base64 48` output can never collide.
     */
    private static final Set<String> PUBLIC_PLACEHOLDER_SECRETS = Set.of(
            "change_me_to_a_long_random_base64_secret",   // application.properties.example
            "set_a_long_random_base64_secret");           // .env.example (31 bytes; also too short)

    // Fail fast at startup on a missing/weak/public secret (HS256 needs a 256-bit key), rather than
    // lazily on the first token operation with an opaque WeakKeyException.
    @PostConstruct
    void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "app.jwt.secret is not configured. Set JWT_SECRET in the environment "
                + "(>=32 bytes), e.g. `openssl rand -base64 48`.");
        }
        if (PUBLIC_PLACEHOLDER_SECRETS.contains(jwtSecret.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                "app.jwt.secret is still the placeholder from a config template, which is public. "
                + "Set a real JWT_SECRET, e.g. `openssl rand -base64 48`.");
        }
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret must be at least 32 bytes (256 bits) for HS256. "
                + "Generate a strong secret, e.g. `openssl rand -base64 48`.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private SecretKey getSigningKey() {
        return signingKey;
    }

    /**
     * Issue a session token expiring exactly {@code jwtExpirationMs} from now. There is no
     * renewal path: once this expires the caller is anonymous and must log in again.
     */
    public String generateToken(String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                // Exact issue time, because the standard `iat` is defined in SECONDS and is
                // therefore floored. Revocation compares this against the account's
                // session_valid_from, and at second resolution a password change and the login
                // right after it land in the same second: either the change fails to revoke, or
                // the fresh login is refused by the change that preceded it. Both ends of this
                // claim are ours, so the precision is free.
                .claim("iatMs", now)
                .issuedAt(new Date(now))
                .expiration(new Date(now + jwtExpirationMs))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String getUsernameFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String getRoleFromToken(String token) {
        return extractClaim(token, claims -> (String) claims.get("role"));
    }

    /** When this token was minted. Compared against the account's session_valid_from, so a token
     *  predating a password change, a DOB reset or a recreated username is refused
     *  (AccountExistenceFilter). JWT `iat` has SECOND resolution — the comparison is strictly
     *  "older than", so a token minted in the same second as the account is not killed by its
     *  own creation. */
    public Instant getIssuedAtFromToken(String token) {
        Number exact = extractClaim(token, claims -> (Number) claims.get("iatMs"));
        if (exact != null) {
            return Instant.ofEpochMilli(exact.longValue());
        }
        // A token minted before iatMs existed. Second resolution is enough for it: V7 backfilled
        // every account's session_valid_from with the migration time, so all such tokens are
        // already older than their account and refused on the next request either way.
        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        return issuedAt == null ? null : issuedAt.toInstant();
    }

    private Date getExpirationDateFromToken(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /** Seconds until expiry. Returned to the SPA on login so it can schedule the automatic
     *  sign-out — it can't decode the exp itself from the httpOnly cookie. */
    public long secondsUntilExpiry(String token) {
        return Math.max(0, (getExpirationDateFromToken(token).getTime() - System.currentTimeMillis()) / 1000);
    }

    private boolean isTokenExpired(String token) {
        return getExpirationDateFromToken(token).before(new Date());
    }

    public boolean validateToken(String token) {
        try {
            extractAllClaims(token);
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }
}