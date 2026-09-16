package com.acme.salary.security.jwt;

import com.acme.salary.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies the API's bearer tokens (FR-1.1, FR-1.7).
 *
 * <p>No I/O and no security context: given a clock and a key this is a pure
 * string-to-claims function, which is what makes expiry and tamper cases testable without
 * a running application. Deciding whether a verified caller may do something is somebody
 * else's job — see {@link JwtAuthenticationFilter}.
 *
 * <p>Time comes from the injected {@link java.time.Clock} (architecture §9) on both the
 * issuing and the parsing side, so a test can mint a token and then move past its expiry.
 */
@Component
public class JwtTokenService {

    /**
     * Short claim names, because a JWT travels on every request. The values are what
     * matters, not the keys, and {@code sub}/{@code exp} already follow the same
     * convention in RFC 7519.
     */
    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_VERSION = "tv";
    private static final String CLAIM_TOKEN_TYPE = "typ";

    private final SecretKey signingKey;
    private final Duration accessTokenLifetime;
    private final Duration refreshTokenLifetime;
    private final java.time.Clock clock;

    public JwtTokenService(JwtProperties properties, java.time.Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenLifetime = Duration.ofMinutes(properties.expiryMinutes());
        this.refreshTokenLifetime = Duration.ofDays(properties.refreshExpiryDays());
        this.clock = clock;
    }

    /** The credential a client sends on business requests. */
    public String issueAccessToken(Long userId, String email, Role role, int tokenVersion) {
        return issue(userId, email, role, tokenVersion, TokenType.ACCESS, accessTokenLifetime);
    }

    /** The longer-lived token a client exchanges for a new access token. */
    public String issueRefreshToken(Long userId, String email, Role role, int tokenVersion) {
        return issue(userId, email, role, tokenVersion, TokenType.REFRESH, refreshTokenLifetime);
    }

    /** Published so the login response can tell a client when to refresh. */
    public Duration accessTokenLifetime() {
        return accessTokenLifetime;
    }

    /**
     * Verifies a token's signature, expiry and type, and returns what it asserts.
     *
     * @param expectedType the use this token is being presented for; a refresh token
     *     offered as a credential is rejected here rather than silently accepted
     * @throws InvalidTokenException on any failure — see that class on why the cases are
     *     not distinguished to the caller
     */
    public TokenClaims parse(String token, TokenType expectedType) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("no token presented");
        }
        Claims claims = verify(token);
        TokenType actualType = readType(claims);
        if (actualType != expectedType) {
            throw new InvalidTokenException(
                    "token is a " + actualType + " token but a " + expectedType + " token is required");
        }
        return new TokenClaims(
                readUserId(claims),
                claims.getSubject(),
                readRole(claims),
                readTokenVersion(claims),
                actualType);
    }

    private String issue(
            Long userId, String email, Role role, int tokenVersion, TokenType type, Duration lifetime) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(CLAIM_TOKEN_TYPE, type.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(lifetime)))
                .signWith(signingKey)
                .compact();
    }

    private Claims verify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    // JJWT's own Clock, not java.time's, and hence the lambda: without it
                    // the library reads the system clock and a test cannot reach expiry.
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    // parseSignedClaims, not parseClaimsJws on an unsecured token: an
                    // alg=none token must fail, not parse.
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException("token expired at " + e.getClaims().getExpiration(), e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("token could not be verified", e);
        }
    }

    private static Long readUserId(Claims claims) {
        Number userId = claims.get(CLAIM_USER_ID, Number.class);
        if (userId == null) {
            throw new InvalidTokenException("token carries no " + CLAIM_USER_ID + " claim");
        }
        return userId.longValue();
    }

    private static Role readRole(Claims claims) {
        return readEnum(Role.class, claims.get(CLAIM_ROLE, String.class), CLAIM_ROLE);
    }

    private static TokenType readType(Claims claims) {
        return readEnum(TokenType.class, claims.get(CLAIM_TOKEN_TYPE, String.class), CLAIM_TOKEN_TYPE);
    }

    private static int readTokenVersion(Claims claims) {
        Number tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Number.class);
        if (tokenVersion == null) {
            throw new InvalidTokenException("token carries no " + CLAIM_TOKEN_VERSION + " claim");
        }
        return tokenVersion.intValue();
    }

    /**
     * A claim whose value is outside the enum is a bad token, not a server error: the
     * token may predate a renamed role. It must land on 401, so it cannot be allowed to
     * escape as {@link IllegalArgumentException}.
     */
    private static <E extends Enum<E>> E readEnum(Class<E> type, String value, String claimName) {
        if (value == null) {
            throw new InvalidTokenException("token carries no " + claimName + " claim");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("token carries an unrecognised " + claimName + " claim", e);
        }
    }
}
