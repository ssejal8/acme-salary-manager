package com.acme.salary.security.jwt;

import com.acme.salary.security.Role;

/**
 * What a verified token asserts.
 *
 * <p>Deliberately not a {@code CurrentUser}: these are claims a client presented, and
 * being signed makes them unforged, not current. The role here may be stale — a
 * demotion five minutes ago is not reflected in a token issued an hour ago — which is why
 * the filter resolves the caller against the database rather than trusting this
 * (architecture §8.2).
 *
 * @param userId the {@code users.id} the token was issued for
 * @param email the login identifier, and the token's subject
 * @param role the role as it stood when the token was issued
 * @param tokenVersion the user's {@code token_version} at issue time; a mismatch against
 *     the stored value is what invalidates outstanding tokens after a password change
 * @param type whether this may be used as a credential or only to refresh
 */
public record TokenClaims(Long userId, String email, Role role, int tokenVersion, TokenType type) {
}
