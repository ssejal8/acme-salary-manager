package com.acme.salary.security.dto;

/**
 * What a successful login or refresh returns (FR-1.1, FR-1.7).
 *
 * <p>The authenticated user travels with the tokens so the SPA can render its shell
 * without a second round trip — there is no {@code /auth/me} in the API surface, and the
 * role it needs is already known at this point.
 *
 * @param tokenType always {@code Bearer}; sent so a client can build the header without
 *     hardcoding a scheme
 * @param expiresIn access token lifetime in seconds, so a client can schedule a refresh
 *     rather than wait for a 401. Seconds, not an absolute instant, because a client's
 *     clock may be wrong and only the interval is meaningful to it.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        AuthenticatedUserResponse user) {

    private static final String BEARER = "Bearer";

    public static TokenResponse of(
            String accessToken, String refreshToken, long expiresInSeconds, AuthenticatedUserResponse user) {
        return new TokenResponse(accessToken, refreshToken, BEARER, expiresInSeconds, user);
    }
}
