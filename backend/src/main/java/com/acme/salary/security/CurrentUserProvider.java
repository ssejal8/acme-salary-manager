package com.acme.salary.security;

import java.util.Optional;

/**
 * Resolves who is making the current request.
 *
 * <p>The port exists so business code can record an actor without reading the security
 * context itself, and so tests can supply an actor without a token.
 */
public interface CurrentUserProvider {

    Optional<CurrentUser> find();

    /**
     * @throws org.springframework.security.access.AccessDeniedException when there is no
     *     authenticated caller — every write in this system must be attributable
     */
    CurrentUser require();
}
