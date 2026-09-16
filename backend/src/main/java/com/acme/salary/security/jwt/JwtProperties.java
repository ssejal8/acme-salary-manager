package com.acme.salary.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token signing and lifetime settings, bound from {@code app.jwt}.
 *
 * <p>Validated at startup rather than at first use. A signing key that is missing, or too
 * short for HMAC-SHA256, must stop the application coming up — the alternative is an API
 * that starts cleanly and then fails every login, or worse, signs tokens with a weak key.
 * {@code application.yml} deliberately gives {@code secret} no default for the same
 * reason.
 *
 * @param secret HMAC signing key; at least 32 bytes, per {@link #MINIMUM_SECRET_LENGTH}
 * @param expiryMinutes access token lifetime, capped by FR-1.7 at 60 minutes
 * @param refreshExpiryDays refresh token lifetime (FR-1.7)
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @Positive int expiryMinutes,
        @Positive int refreshExpiryDays) {

    /**
     * HMAC-SHA256 requires a key at least as long as its output (RFC 7518 §3.2), and JJWT
     * refuses to sign with less. Checking here turns that into a startup failure with a
     * readable message instead of a 500 on the first login.
     */
    public static final int MINIMUM_SECRET_LENGTH = 32;

    public JwtProperties {
        if (secret != null && secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least " + MINIMUM_SECRET_LENGTH
                            + " characters for HMAC-SHA256; got " + secret.length());
        }
    }
}
