package com.acme.salary.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.security.UserAccountService.ProvisionedLogin;
import com.acme.salary.security.dto.ChangePasswordRequest;
import com.acme.salary.support.UserFixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Changing your own password (FR-1.6) and provisioning a login (FR-2.7).
 *
 * <p>Uses a real {@link BCryptPasswordEncoder} rather than a mock. The behaviour under
 * test is largely "does this password match the stored hash", and a mocked encoder would
 * let a test pass while the real comparison did the opposite. BCrypt at its lowest
 * strength is fast enough for a unit test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAccountServiceTest {

    private static final String CURRENT = "Current@12345";

    @Mock
    private UserRepository users;

    @Mock
    private CurrentUserProvider currentUser;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserAccountService service;
    private User asha;

    @Captor
    private ArgumentCaptor<User> savedUser;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(users, passwordEncoder, currentUser);
        asha = UserFixtures.user(9L, "asha.menon@acme.test", Role.EMPLOYEE,
                passwordEncoder.encode(CURRENT));
        when(currentUser.require())
                .thenReturn(new CurrentUser(9L, "asha.menon@acme.test", Role.EMPLOYEE));
        when(users.findById(9L)).thenReturn(Optional.of(asha));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Nested
    @DisplayName("changing your own password")
    class ChangingPassword {

        @Test
        void replacesTheHashWhenTheCurrentPasswordIsConfirmed() {
            service.changeOwnPassword(new ChangePasswordRequest(CURRENT, "Replacement@123"));

            assertThat(passwordEncoder.matches("Replacement@123", asha.getPasswordHash())).isTrue();
            assertThat(passwordEncoder.matches(CURRENT, asha.getPasswordHash())).isFalse();
        }

        @Test
        void invalidatesEveryOutstandingToken() {
            // ADR-004: tokens cannot be revoked, so tokenVersion is what kills them. A
            // stolen token must die with the password behind it.
            int before = asha.getTokenVersion();

            service.changeOwnPassword(new ChangePasswordRequest(CURRENT, "Replacement@123"));

            assertThat(asha.getTokenVersion()).isEqualTo(before + 1);
        }

        @Test
        void refusesAWrongCurrentPasswordAsAFieldErrorNotAnUnauthorised() {
            // The caller is authenticated, so this is a failed confirmation. A 401 would
            // make the client's interceptor end the session over a typo.
            assertThatThrownBy(() -> service.changeOwnPassword(
                    new ChangePasswordRequest("not-the-password", "Replacement@123")))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("currentPassword")));

            assertThat(passwordEncoder.matches(CURRENT, asha.getPasswordHash())).isTrue();
            assertThat(asha.getTokenVersion()).isZero();
        }

        @Test
        void refusesAPasswordShorterThanThePolicy() {
            assertThatThrownBy(() -> service.changeOwnPassword(
                    new ChangePasswordRequest(CURRENT, "short")))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> {
                                assertThat(error.field()).isEqualTo("newPassword");
                                assertThat(error.message()).contains("at least 10");
                            }));
        }

        @Test
        void refusesOneLongerThanBcryptActuallyHashes() {
            // BCrypt silently ignores bytes past 72, so accepting more would give a false
            // sense of strength.
            assertThatThrownBy(() -> service.changeOwnPassword(
                    new ChangePasswordRequest(CURRENT, "x".repeat(73))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesReusingTheCurrentPassword() {
            assertThatThrownBy(() -> service.changeOwnPassword(
                    new ChangePasswordRequest(CURRENT, CURRENT)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Validation failed");

            assertThat(asha.getTokenVersion()).isZero();
        }

        @Test
        void changesOnlyTheCallersOwnAccount() {
            // There is no user id in the request, so the only account reachable is the
            // one the token resolves to.
            service.changeOwnPassword(new ChangePasswordRequest(CURRENT, "Replacement@123"));

            verify(users).findById(9L);
        }
    }

    @Nested
    @DisplayName("provisioning a login for a new employee")
    class Provisioning {

        @Test
        void createsAnEmployeeLoginWithTheWorkEmailAsItsUsername() {
            when(users.findByEmail("ravi.iyer@acme.test")).thenReturn(Optional.empty());

            ProvisionedLogin provisioned = service.provisionLoginFor("ravi.iyer@acme.test");

            verify(users).save(savedUser.capture());
            assertThat(savedUser.getValue().getEmail()).isEqualTo("ravi.iyer@acme.test");
            assertThat(savedUser.getValue().getRole()).isEqualTo(Role.EMPLOYEE);
            assertThat(savedUser.getValue().isEnabled()).isTrue();
            assertThat(provisioned.temporaryPassword()).isNotBlank();
        }

        @Test
        void storesTheGeneratedPasswordOnlyAsAHash() {
            // FR-1.2. The password is returned once; what is kept is a BCrypt hash.
            when(users.findByEmail(any())).thenReturn(Optional.empty());

            ProvisionedLogin provisioned = service.provisionLoginFor("ravi.iyer@acme.test");

            verify(users).save(savedUser.capture());
            String hash = savedUser.getValue().getPasswordHash();
            assertThat(hash).isNotEqualTo(provisioned.temporaryPassword()).startsWith("$2");
            assertThat(passwordEncoder.matches(provisioned.temporaryPassword(), hash)).isTrue();
        }

        @Test
        void generatesAPasswordThatSatisfiesItsOwnPolicy() {
            // A generated credential the change-password endpoint would reject would be a
            // quiet contradiction.
            when(users.findByEmail(any())).thenReturn(Optional.empty());

            String generated = service.provisionLoginFor("ravi.iyer@acme.test").temporaryPassword();

            assertThat(generated.length()).isGreaterThanOrEqualTo(10).isLessThanOrEqualTo(72);
        }

        @Test
        void normalisesTheAddressBeforeLookingForAnExistingLogin() {
            when(users.findByEmail("ravi.iyer@acme.test")).thenReturn(Optional.of(asha));

            service.provisionLoginFor("  Ravi.Iyer@ACME.test  ");

            verify(users).findByEmail("ravi.iyer@acme.test");
            verify(users, never()).save(any(User.class));
        }

        @Test
        void linksAnExistingLoginRatherThanReplacingIt() {
            // The HR operator who is also on the payroll. Re-provisioning would either
            // fail on the unique index or quietly demote them to EMPLOYEE.
            User hr = UserFixtures.user(2L, "hr@acme.test", Role.HR, passwordEncoder.encode("x"));
            when(users.findByEmail("hr@acme.test")).thenReturn(Optional.of(hr));

            ProvisionedLogin provisioned = service.provisionLoginFor("hr@acme.test");

            assertThat(provisioned.userId()).isEqualTo(2L);
            // No credential to hand over, and the role is untouched.
            assertThat(provisioned.temporaryPassword()).isNull();
            assertThat(hr.getRole()).isEqualTo(Role.HR);
            verify(users, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("moving a login to a corrected address")
    class ChangingEmail {

        @Test
        void followsTheEmployeeRecord() {
            when(users.existsByEmail("asha.rao@acme.test")).thenReturn(false);

            service.changeLoginEmail(9L, "Asha.Rao@acme.test");

            assertThat(asha.getEmail()).isEqualTo("asha.rao@acme.test");
        }

        @Test
        void doesNotSignThePersonOutOverACorrection() {
            // A token identifies its user by id, so the session still belongs to the same
            // person. Bumping the version here would log them out for somebody else's typo.
            service.changeLoginEmail(9L, "asha.rao@acme.test");

            assertThat(asha.getTokenVersion()).isZero();
        }

        @Test
        void isANoOpWhenTheAddressHasNotActuallyChanged() {
            service.changeLoginEmail(9L, "asha.menon@acme.test");

            assertThat(asha.getEmail()).isEqualTo("asha.menon@acme.test");
            verify(users, never()).existsByEmail(any());
        }

        @Test
        void refusesAnAddressAnotherLoginAlreadyUses() {
            when(users.existsByEmail("hr@acme.test")).thenReturn(true);

            assertThatThrownBy(() -> service.changeLoginEmail(9L, "hr@acme.test"))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(thrown -> assertThat(((ConflictException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("workEmail")));

            assertThat(asha.getEmail()).isEqualTo("asha.menon@acme.test");
        }
    }
}
