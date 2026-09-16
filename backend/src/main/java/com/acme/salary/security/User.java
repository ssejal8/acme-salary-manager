package com.acme.salary.security;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A login.
 *
 * <p>Deliberately minimal at this point: enough to attribute a change to an actor, which
 * every salary write requires. Password verification, token issuing and the refresh flow
 * arrive with the authentication feature and will build on this entity rather than
 * replace it.
 *
 * <p>The password hash is never exposed: no getter returns it to application code outside
 * this package's future authentication service, and no DTO carries it (FR-1.2).
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Incremented on password change and on deactivation. Stateless tokens cannot be
     * revoked (ADR-004); comparing this against a token's claim is what invalidates
     * outstanding ones.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    protected User() {
        // for JPA
    }

    public User(String email, String passwordHash, Role role) {
        this.email = requireEmail(email);
        this.passwordHash = requireHash(passwordHash);
        this.role = role == null ? Role.EMPLOYEE : role;
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
        this.tokenVersion++;
    }

    public void enable() {
        this.enabled = true;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = requireHash(newPasswordHash);
        this.tokenVersion++;
    }

    public CurrentUser toCurrentUser() {
        return new CurrentUser(getId(), email, role);
    }

    private static String requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw ValidationException.field("email", "must not be blank");
        }
        // The database enforces the same rule: case must not create a second account.
        return email.strip().toLowerCase();
    }

    private static String requireHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw ValidationException.field("password", "must not be blank");
        }
        return passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    /** Package-private: only the authentication service has any business reading this. */
    String getPasswordHash() {
        return passwordHash;
    }
}
