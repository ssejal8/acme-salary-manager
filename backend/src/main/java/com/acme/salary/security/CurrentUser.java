package com.acme.salary.security;

/**
 * The authenticated caller, as other features need to see them.
 *
 * <p>Features that must attribute a change — {@code salary_structures.created_by}, every
 * audit event — depend on this record and on {@link CurrentUserProvider}, never on the
 * {@link User} entity. That keeps the authentication feature's internals behind a port
 * (ADR-001).
 */
public record CurrentUser(Long id, String email, Role role) {
}
