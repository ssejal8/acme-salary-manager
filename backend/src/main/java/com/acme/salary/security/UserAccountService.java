package com.acme.salary.security;

import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.security.dto.ChangePasswordRequest;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts: changing your own password (FR-1.6), and provisioning a login for a new
 * employee (FR-2.7).
 *
 * <p>Separate from {@link AuthenticationService}, which answers "who is this caller" for
 * every request. These are infrequent writes with different collaborators, and keeping
 * them apart means the hot path has nothing to do with provisioning.
 *
 * <p>This class is also the security feature's published port for the employee feature.
 * The employee package must not construct a {@link User} itself — the password policy,
 * the hashing and the decision to reuse an existing login all belong here (ADR-001).
 */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    /**
     * Minimum password length.
     *
     * <p>Length, and nothing else. A composition rule — an uppercase, a digit, a symbol —
     * mostly produces {@code Password1!} and a sticky note; length is the property that
     * actually costs an attacker something. BCrypt's own ceiling is 72 bytes, which is why
     * there is a maximum at all.
     */
    private static final int MIN_PASSWORD_LENGTH = 10;
    private static final int MAX_PASSWORD_LENGTH = 72;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;

    public UserAccountService(
            UserRepository users, PasswordEncoder passwordEncoder, CurrentUserProvider currentUser) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    /**
     * Changes the caller's own password after confirming the current one (FR-1.6).
     *
     * <p>Three decisions worth knowing:
     *
     * <ul>
     *   <li><b>It is always the caller's own.</b> No user id is accepted, so this endpoint
     *       cannot be aimed at another account however the request is built — the same
     *       reason {@code /payslips/me} takes no id. An administrative reset is a
     *       different operation and does not exist yet.
     *   <li><b>The current password is required</b>, and a wrong one is a 400 rather than
     *       a 401: the caller is authenticated, so this is a failed confirmation, not a
     *       failed authentication. Answering 401 would make the client's interceptor end
     *       the session over a typo.
     *   <li><b>Every outstanding token stops working.</b> {@code changePassword} bumps
     *       {@code tokenVersion}, and the filter compares that on every request (ADR-004),
     *       so a stolen token is dead as soon as the password behind it changes. The cost
     *       is that the caller has to sign in again, which is the correct trade.
     * </ul>
     */
    @Transactional
    public void changeOwnPassword(ChangePasswordRequest request) {
        CurrentUser caller = currentUser.require();
        User user = users.findById(caller.id())
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated user " + caller.id() + " no longer exists"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.info("User {} failed to confirm their current password", caller.id());
            throw ValidationException.field("currentPassword", "is not correct");
        }
        validate(request.newPassword());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ValidationException.field("newPassword", "must differ from the current password");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        log.info("User {} changed their password; outstanding tokens are now invalid", caller.id());
    }

    /**
     * Ensures a login exists for an employee's work email (FR-2.7).
     *
     * <p>An existing account for that address is <b>linked rather than replaced</b>, and
     * that is the important case rather than an edge one: the HR operator who is also on
     * the payroll already has a login, and re-provisioning would either fail on the unique
     * index or quietly demote them to EMPLOYEE.
     *
     * <p>A new account gets a generated password which is returned <b>once</b> and never
     * again — it is stored only as a BCrypt hash (FR-1.2) and never logged. The employee
     * changes it with {@link #changeOwnPassword}.
     *
     * @return the login's id, and the temporary password when one was created
     */
    @Transactional
    public ProvisionedLogin provisionLoginFor(String workEmail) {
        String email = workEmail.strip().toLowerCase();

        return users.findByEmail(email)
                .map(existing -> {
                    log.debug("Linking existing login {} to a new employee record", existing.getId());
                    return new ProvisionedLogin(existing.getId(), null);
                })
                .orElseGet(() -> {
                    String temporaryPassword = generatePassword();
                    User created = users.save(new User(
                            email, passwordEncoder.encode(temporaryPassword), Role.EMPLOYEE));
                    log.info("Provisioned EMPLOYEE login {} for a new employee record",
                            created.getId());
                    return new ProvisionedLogin(created.getId(), temporaryPassword);
                });
    }

    /**
     * Moves a login to a new address when an employee's work email is corrected.
     *
     * <p>Without this the two drift apart: the work email is the username (FR-2.7), so an
     * employee whose record was corrected would keep signing in with the old address while
     * their record showed the new one.
     *
     * @throws ConflictException if another account already uses that address
     */
    @Transactional
    public void changeLoginEmail(Long userId, String newEmail) {
        String email = newEmail.strip().toLowerCase();
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("login " + userId + " does not exist"));
        if (user.getEmail().equals(email)) {
            return;
        }
        if (users.existsByEmail(email)) {
            throw ConflictException.field("workEmail", email + " is already used by another login");
        }
        user.changeEmail(email);
        log.info("Login {} moved to a new address with its employee record", userId);
    }

    private void validate(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ValidationException.field(
                    "newPassword", "must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            // BCrypt silently ignores bytes past 72, so a longer password would give a
            // false sense of strength.
            throw ValidationException.field(
                    "newPassword", "must be at most " + MAX_PASSWORD_LENGTH + " characters");
        }
    }

    /** A URL-safe random password, comfortably longer than the minimum. */
    private static String generatePassword() {
        byte[] entropy = new byte[12];
        RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /**
     * The result of provisioning.
     *
     * @param temporaryPassword null when an existing login was linked — there is no
     *     password to hand over, because the account already had one
     */
    public record ProvisionedLogin(Long userId, String temporaryPassword) {
    }
}
