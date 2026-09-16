package com.acme.salary.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A refresh token offered in exchange for a new access token (FR-1.7).
 *
 * <p>Sent in the body rather than the {@code Authorization} header so it cannot be
 * attached to a business request by an interceptor that does not know the difference —
 * the token type claim would reject it, but keeping the two out of the same channel means
 * that guard is never the only one.
 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
