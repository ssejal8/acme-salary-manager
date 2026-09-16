package com.acme.salary.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Token issuing and verification, with no application context and no database.
 *
 * <p>Time is driven by a {@link MutableClock} rather than by sleeping, so the expiry cases
 * — the ones that actually matter for FR-1.7 — run instantly and deterministically.
 */
class JwtTokenServiceTest {

    private static final String SECRET = "test-only-signing-key-at-least-32-bytes-long";
    private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");

    private static final Long USER_ID = 7L;
    private static final String EMAIL = "hr@acme.test";
    private static final int TOKEN_VERSION = 3;

    private final MutableClock clock = new MutableClock(NOW);
    private final JwtTokenService tokens = service(SECRET, clock);

    private static JwtTokenService service(String secret, Clock clock) {
        return new JwtTokenService(new JwtProperties(secret, 60, 7), clock);
    }

    private String accessToken() {
        return tokens.issueAccessToken(USER_ID, EMAIL, Role.HR, TOKEN_VERSION);
    }

    @Test
    void anAccessTokenRoundTripsEveryClaimItWasIssuedWith() {
        TokenClaims claims = tokens.parse(accessToken(), TokenType.ACCESS);

        assertThat(claims.userId()).isEqualTo(USER_ID);
        assertThat(claims.email()).isEqualTo(EMAIL);
        assertThat(claims.role()).isEqualTo(Role.HR);
        assertThat(claims.tokenVersion()).isEqualTo(TOKEN_VERSION);
        assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
    }

    @Test
    void aRefreshTokenIsNotAcceptedAsACredential() {
        // Otherwise the 60-minute access window in FR-1.7 would be worth nothing: a
        // client could simply present its 7-day token on every business request.
        String refreshToken = tokens.issueRefreshToken(USER_ID, EMAIL, Role.HR, TOKEN_VERSION);

        assertThatThrownBy(() -> tokens.parse(refreshToken, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("REFRESH");
    }

    @Test
    void anAccessTokenCannotBeUsedToRefresh() {
        assertThatThrownBy(() -> tokens.parse(accessToken(), TokenType.REFRESH))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anAccessTokenIsStillValidJustBeforeItsExpiry() {
        String token = accessToken();

        clock.advance(Duration.ofMinutes(59));

        assertThatCode(() -> tokens.parse(token, TokenType.ACCESS)).doesNotThrowAnyException();
    }

    @Test
    void anAccessTokenIsRejectedAfterItsExpiry() {
        String token = accessToken();

        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> tokens.parse(token, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aRefreshTokenOutlivesTheAccessTokenIssuedBesideIt() {
        String accessToken = accessToken();
        String refreshToken = tokens.issueRefreshToken(USER_ID, EMAIL, Role.HR, TOKEN_VERSION);

        clock.advance(Duration.ofDays(6));

        assertThatThrownBy(() -> tokens.parse(accessToken, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
        assertThatCode(() -> tokens.parse(refreshToken, TokenType.REFRESH)).doesNotThrowAnyException();
    }

    @Test
    void aRefreshTokenIsRejectedAfterSevenDays() {
        String refreshToken = tokens.issueRefreshToken(USER_ID, EMAIL, Role.HR, TOKEN_VERSION);

        clock.advance(Duration.ofDays(7).plusMinutes(1));

        assertThatThrownBy(() -> tokens.parse(refreshToken, TokenType.REFRESH))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        String foreignToken = service("a-different-key-also-at-least-32-bytes-long", clock)
                .issueAccessToken(USER_ID, EMAIL, Role.ADMIN, TOKEN_VERSION);

        assertThatThrownBy(() -> tokens.parse(foreignToken, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aTamperedRoleClaimIsRejectedRatherThanHonoured() {
        // The attack this defends against: an HR token edited to claim ADMIN. The payload
        // is only base64, so editing it is trivial — the signature is what stops it.
        String token = accessToken();
        String[] segments = token.split("\\.");
        String tamperedPayload = segments[1].substring(0, segments[1].length() - 2) + "XY";
        String tampered = segments[0] + "." + tamperedPayload + "." + segments[2];

        assertThatThrownBy(() -> tokens.parse(tampered, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anUnsignedTokenIsRejected() {
        String token = accessToken();
        String unsigned = token.substring(0, token.lastIndexOf('.') + 1);

        assertThatThrownBy(() -> tokens.parse(unsigned, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void somethingThatIsNotATokenIsRejectedAsAnInvalidTokenNotAsAServerError() {
        // JJWT throws IllegalArgumentException for malformed input; letting that escape
        // would turn a bad request into a 500.
        assertThatThrownBy(() -> tokens.parse("not-a-token", TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anAbsentOrBlankTokenIsRejected() {
        assertThatThrownBy(() -> tokens.parse(null, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> tokens.parse("   ", TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void theAccessTokenLifetimeIsPublishedForTheLoginResponse() {
        assertThat(tokens.accessTokenLifetime()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void aSecretTooShortForHmacSha256IsRejectedBeforeAnythingIsSigned() {
        // Startup must fail loudly. The alternative is an application that comes up and
        // then answers 500 to every login.
        assertThatThrownBy(() -> new JwtProperties("too-short", 60, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least " + JwtProperties.MINIMUM_SECRET_LENGTH);
    }

    /** A clock a test can move, so expiry is reachable without waiting for it. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
