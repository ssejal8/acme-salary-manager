package com.acme.salary.security;

import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller from the security context and looks up their login row.
 *
 * <p>The authenticated name is the user's email — the username throughout this system
 * (FR-2.7). Until the JWT filter lands the security context is only ever populated in
 * tests, which is why every write endpoint answers 401 in the meantime.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    private final UserRepository users;

    public SecurityContextCurrentUserProvider(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return users.findByEmail(authentication.getName().toLowerCase())
                .filter(User::isEnabled)
                .map(User::toCurrentUser);
    }

    @Override
    public CurrentUser require() {
        return find().orElseThrow(() -> new AccessDeniedException(
                // Deliberately vague: the caller learns nothing about which accounts exist.
                "the request could not be attributed to an enabled user"));
    }
}
