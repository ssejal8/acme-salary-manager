package com.acme.salary.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.security.dto.LoginRequest;
import com.acme.salary.security.dto.TokenResponse;
import com.acme.salary.security.jwt.InvalidTokenException;
import com.acme.salary.security.jwt.JwtProperties;
import com.acme.salary.security.jwt.JwtTokenService;
import com.acme.salary.security.jwt.TokenType;
import com.acme.salary.support.UserFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Login, refresh and per-request token resolution, with the repository mocked.
 *
 * <p>The password encoder is a real {@link BCryptPasswordEncoder} rather than a mock. A
 * mocked encoder would let every one of these tests pass against an implementation that
 * compared hashes with {@code equals}, which is the bug most worth not having.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final String EMAIL = "hr@acme.test";
    private static final String PASSWORD = "Hr@12345";
    private static final long USER_ID = 7L;

    @Mock
    private UserRepository users;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneOffset.UTC);
    private final JwtTokenService tokens = new JwtTokenService(
            new JwtProperties("test-only-signing-key-at-least-32-bytes-long", 60, 7), clock);

    private AuthenticationService authentication;
    private User user;

    @BeforeEach
    void setUp() {
        authentication = new AuthenticationService(users, passwordEncoder, tokens);
        user = UserFixtures.user(USER_ID, EMAIL, Role.HR, passwordEncoder.encode(PASSWORD));
    }

    private void userExists() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private TokenResponse login() {
        return authentication.login(new LoginRequest(EMAIL, PASSWORD));
    }

    @Test
    void correctCredentialsYieldATokenPairAndTheCaller() {
        userExists();

        TokenResponse response = login();

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.user().id()).isEqualTo(USER_ID);
        assertThat(response.user().email()).isEqualTo(EMAIL);
        assertThat(response.user().role()).isEqualTo(Role.HR);
    }

    @Test
    void theAccessAndRefreshTokensAreDistinctAndTypedAsSuch() {
        userExists();

        TokenResponse response = login();

        assertThat(response.accessToken()).isNotEqualTo(response.refreshToken());
        assertThat(tokens.parse(response.accessToken(), TokenType.ACCESS).type())
                .isEqualTo(TokenType.ACCESS);
        assertThat(tokens.parse(response.refreshToken(), TokenType.REFRESH).type())
                .isEqualTo(TokenType.REFRESH);
    }

    @Test
    void theEmailIsMatchedWithoutRegardToCaseOrSurroundingSpace() {
        // Emails are stored lowercased and the column has a CHECK constraint saying so,
        // so a user typing "HR@Acme.test" must still be found.
        userExists();

        TokenResponse response = authentication.login(new LoginRequest("  HR@Acme.test  ", PASSWORD));

        assertThat(response.user().email()).isEqualTo(EMAIL);
        verify(users).findByEmail(EMAIL);
    }

    @Test
    void aWrongPasswordIsRejected() {
        userExists();

        assertThatThrownBy(() -> authentication.login(new LoginRequest(EMAIL, "Wrong@123")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void anUnknownEmailIsRejectedWithTheSameMessageAsAWrongPassword() {
        // Any difference between these two is an account-enumeration oracle.
        when(users.findByEmail("nobody@acme.test")).thenReturn(Optional.empty());
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        Throwable unknownEmail = catchLoginFailure("nobody@acme.test", PASSWORD);
        Throwable wrongPassword = catchLoginFailure(EMAIL, "Wrong@123");

        assertThat(unknownEmail).isInstanceOf(BadCredentialsException.class);
        assertThat(wrongPassword).isInstanceOf(BadCredentialsException.class);
        assertThat(unknownEmail).hasMessage(wrongPassword.getMessage());
    }

    @Test
    void theUnknownEmailPathStillComparesAHashSoItIsNotFasterThanAWrongPassword() {
        // A timing oracle is the same leak by another channel: skipping BCrypt when the
        // address is unknown answers in microseconds where a real account takes tens of
        // milliseconds. The service holds a hash of a random string for exactly this.
        when(users.findByEmail("nobody@acme.test")).thenReturn(Optional.empty());

        long unknownEmailNanos = timeLoginFailure("nobody@acme.test", PASSWORD);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        long wrongPasswordNanos = timeLoginFailure(EMAIL, "Wrong@123");

        // A loose bound, because wall-clock timing on a shared CI machine is noisy. What
        // it does catch is the real regression: an early return, which would make the
        // unknown-email path orders of magnitude faster rather than merely quicker.
        assertThat(unknownEmailNanos)
                .as("unknown-email path must stay within an order of magnitude of the "
                        + "wrong-password path")
                .isGreaterThan(wrongPasswordNanos / 10);
    }

    @Test
    void aDisabledAccountCannotLogInEvenWithTheRightPassword() {
        user.disable();
        userExists();

        assertThatThrownBy(this::login).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void aValidAccessTokenResolvesToTheCaller() {
        userExists();
        String accessToken = login().accessToken();

        CurrentUser caller = authentication.authenticate(accessToken);

        assertThat(caller.id()).isEqualTo(USER_ID);
        assertThat(caller.email()).isEqualTo(EMAIL);
        assertThat(caller.role()).isEqualTo(Role.HR);
    }

    @Test
    void anAccessTokenStopsWorkingOnceItsUserIsDisabled() {
        // The check a stateless token cannot make for itself, and the reason
        // authenticate() reads the database on every request (ADR-004).
        userExists();
        String accessToken = login().accessToken();

        user.disable();

        assertThatThrownBy(() -> authentication.authenticate(accessToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void anAccessTokenStopsWorkingOnceThePasswordChanges() {
        // FR-1.6's other half: changing a password must log out the sessions that were
        // opened with the old one. changePassword bumps tokenVersion, which no longer
        // matches the claim in an outstanding token.
        userExists();
        String accessToken = login().accessToken();

        user.changePassword(passwordEncoder.encode("Brand@New1"));

        assertThatThrownBy(() -> authentication.authenticate(accessToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("invalidated");
    }

    @Test
    void anAccessTokenForADeletedUserIsRejectedRatherThanTrusted() {
        userExists();
        String accessToken = login().accessToken();

        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authentication.authenticate(accessToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aRefreshTokenCannotBeUsedAsACredential() {
        userExists();
        String refreshToken = login().refreshToken();

        assertThatThrownBy(() -> authentication.authenticate(refreshToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refreshingYieldsAFreshAccessTokenAndTheCallerAgain() {
        userExists();
        String refreshToken = login().refreshToken();

        TokenResponse refreshed = authentication.refresh(refreshToken);

        assertThat(tokens.parse(refreshed.accessToken(), TokenType.ACCESS).email()).isEqualTo(EMAIL);
        assertThat(refreshed.user().role()).isEqualTo(Role.HR);
    }

    @Test
    void refreshingReturnsTheSameRefreshTokenRatherThanRotatingIt() {
        // Rotating would slide the 7-day expiry forward on every refresh, and a session
        // that can never end is not a 7-day session (FR-1.7).
        userExists();
        String refreshToken = login().refreshToken();

        assertThat(authentication.refresh(refreshToken).refreshToken()).isEqualTo(refreshToken);
    }

    @Test
    void anAccessTokenCannotBeUsedToRefresh() {
        userExists();
        String accessToken = login().accessToken();

        assertThatThrownBy(() -> authentication.refresh(accessToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refreshingWithADisabledAccountIsRejected() {
        userExists();
        String refreshToken = login().refreshToken();

        user.disable();

        assertThatThrownBy(() -> authentication.refresh(refreshToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refreshingWithSomethingThatIsNotATokenIsRejected() {
        assertThatThrownBy(() -> authentication.refresh("not-a-token"))
                .isInstanceOf(InvalidTokenException.class);
    }

    private Throwable catchLoginFailure(String email, String password) {
        try {
            authentication.login(new LoginRequest(email, password));
            throw new AssertionError("expected the login to be rejected");
        } catch (BadCredentialsException e) {
            return e;
        }
    }

    private long timeLoginFailure(String email, String password) {
        long start = System.nanoTime();
        catchLoginFailure(email, password);
        return System.nanoTime() - start;
    }
}
