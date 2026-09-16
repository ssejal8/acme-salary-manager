package com.acme.salary.security;

import com.acme.salary.security.dto.AuthenticatedUserResponse;
import com.acme.salary.security.dto.LoginRequest;
import com.acme.salary.security.dto.TokenResponse;
import com.acme.salary.security.jwt.InvalidTokenException;
import com.acme.salary.security.jwt.JwtTokenService;
import com.acme.salary.security.jwt.TokenClaims;
import com.acme.salary.security.jwt.TokenType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns credentials into tokens, and tokens back into a caller (FR-1.1, FR-1.7).
 *
 * <p>This class lives in the {@code security} package rather than a sub-package for one
 * reason: {@link User#getPasswordHash()} is package-private, and this is the
 * "authentication service" its comment reserves that access for. Nothing else in the
 * application can read a hash.
 *
 * <p>Token signing and parsing are delegated to {@link JwtTokenService}, which does no
 * I/O. What is added here is the part that needs the database: a signed token proves it
 * was issued by us, not that the account behind it is still usable.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * Failures are indistinguishable to the caller, by requirement of not leaking which
     * accounts exist. The log carries the real reason.
     */
    private static final String GENERIC_FAILURE = "invalid email or password";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;

    /**
     * A hash of a random string generated at startup, used to keep the unknown-email path
     * as expensive as the wrong-password path.
     *
     * <p>Without it, BCrypt runs only when the email exists and the difference is
     * measurable: an unknown address answers in microseconds, a known one in tens of
     * milliseconds. That timing difference is a working account-enumeration oracle, and no
     * amount of care over the response body closes it. Nobody knows this string's
     * preimage, so it can never match.
     */
    private final String timingEqualiserHash;

    public AuthenticationService(
            UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.timingEqualiserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Verifies credentials and issues a token pair (FR-1.1).
     *
     * @throws BadCredentialsException when the email is unknown, the password is wrong, or
     *     the account is disabled — one exception for all three, see {@link
     *     #GENERIC_FAILURE}
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String email = normalise(request.email());
        User user = users.findByEmail(email).orElse(null);

        // Deliberately not short-circuited: the hash comparison runs either way.
        String hash = user == null ? timingEqualiserHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (user == null || !passwordMatches) {
            log.info("Login rejected for {}: {}", email, user == null ? "no such user" : "wrong password");
            throw new BadCredentialsException(GENERIC_FAILURE);
        }
        if (!user.isEnabled()) {
            log.info("Login rejected for {}: account disabled", email);
            throw new BadCredentialsException(GENERIC_FAILURE);
        }

        log.info("Login accepted for {} as {}", email, user.getRole());
        return issuePair(user, tokens.issueRefreshToken(
                user.getId(), user.getEmail(), user.getRole(), user.getTokenVersion()));
    }

    /**
     * Exchanges a refresh token for a fresh access token (FR-1.7).
     *
     * <p>The refresh token itself is returned unchanged rather than rotated. Re-issuing it
     * on every refresh would slide its expiry forward indefinitely, and a session that can
     * never end is not a 7-day session — after seven days the user logs in again, which is
     * what the requirement says.
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        User user = resolve(tokens.parse(refreshToken, TokenType.REFRESH));
        return issuePair(user, refreshToken);
    }

    /**
     * Resolves an access token to the caller it authenticates, or fails.
     *
     * <p>Called on every request by {@code JwtAuthenticationFilter}, which is why it is
     * worth being explicit about the cost: this is one indexed lookup per request. It buys
     * the two checks a stateless token cannot make by itself — that the account is still
     * enabled, and that its {@code tokenVersion} still matches. Skipping it would leave a
     * deactivated user working for up to an hour and a password change not logging anyone
     * out, and those are the mitigations ADR-004 relies on.
     */
    @Transactional(readOnly = true)
    public CurrentUser authenticate(String accessToken) {
        return resolve(tokens.parse(accessToken, TokenType.ACCESS)).toCurrentUser();
    }

    private User resolve(TokenClaims claims) {
        User user = users.findByEmail(normalise(claims.email()))
                .orElseThrow(() -> new InvalidTokenException("token subject no longer exists"));
        if (!user.isEnabled()) {
            throw new InvalidTokenException("account is disabled");
        }
        if (user.getTokenVersion() != claims.tokenVersion()) {
            // Password changed or the account was deactivated and re-enabled since issue.
            throw new InvalidTokenException("token was invalidated by a credential change");
        }
        return user;
    }

    private TokenResponse issuePair(User user, String refreshToken) {
        String accessToken = tokens.issueAccessToken(
                user.getId(), user.getEmail(), user.getRole(), user.getTokenVersion());
        return TokenResponse.of(
                accessToken,
                refreshToken,
                tokens.accessTokenLifetime().toSeconds(),
                AuthenticatedUserResponse.from(user.toCurrentUser()));
    }

    /** Email is the login identifier and is stored lowercased, so case must not matter. */
    private static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase();
    }
}
