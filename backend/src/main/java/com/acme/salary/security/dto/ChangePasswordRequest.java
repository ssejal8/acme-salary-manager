package com.acme.salary.security.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to change your own password (FR-1.6).
 *
 * <p>There is no user id, and its absence is the contract: the account changed is always
 * the authenticated caller's, so there is nothing in the request to point at somebody
 * else. An administrative reset would be a different endpoint with its own authorisation.
 *
 * <p>The length rule lives in the service rather than in an annotation here. It has to be
 * applied to a generated password as well, and one copy of a password policy is the only
 * way it stays one policy.
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword) {
}
