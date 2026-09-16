package com.acme.salary.support;

import com.acme.salary.security.Role;
import com.acme.salary.security.User;

/**
 * Builders for {@link User} in tests that have no database.
 *
 * <p>Ids are assigned through {@link EmployeeFixtures#withId}, the one reflective
 * id-setter in the test sources — {@code BaseEntity} deliberately has no setter because
 * ids are database-generated.
 *
 * <p>There is no helper for reaching a particular token version. A test that needs one
 * calls {@code changePassword} or {@code disable} on the user, which is how the field
 * moves in production; a fixture that set it directly could drift from the behaviour it
 * stands in for.
 */
public final class UserFixtures {

    private UserFixtures() {
    }

    /** An enabled user at token version 0. */
    public static User user(long id, String email, Role role, String passwordHash) {
        return EmployeeFixtures.withId(new User(email, passwordHash, role), id);
    }
}
