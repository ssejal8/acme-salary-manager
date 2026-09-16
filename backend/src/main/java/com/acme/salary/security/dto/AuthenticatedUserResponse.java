package com.acme.salary.security.dto;

import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.Role;

/**
 * Who the caller is, as the client is told.
 *
 * <p>Carries the role so the SPA can render a role-appropriate menu. That is presentation
 * only and is not a control: every endpoint checks the role itself
 * ({@code NFR-2.2}), so a client that lies to itself about this gains nothing.
 *
 * <p>No password field, hash or token version — FR-1.2, and a token version is an
 * implementation detail of revocation.
 */
public record AuthenticatedUserResponse(Long id, String email, Role role) {

    public static AuthenticatedUserResponse from(CurrentUser user) {
        return new AuthenticatedUserResponse(user.id(), user.email(), user.role());
    }
}
