package com.acme.salary.support;

import com.acme.salary.common.persistence.BaseEntity;
import com.acme.salary.employee.Employee;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.Grade;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Builders for tests that need persisted-looking entities without a database.
 *
 * <p>Ids are assigned reflectively because they are database-generated in production —
 * there is deliberately no setter to call (see {@link BaseEntity}).
 */
public final class EmployeeFixtures {

    private EmployeeFixtures() {
    }

    public static Department department(long id, String code, String name) {
        return withId(new Department(code, name), id);
    }

    public static Designation designation(long id, String title) {
        return withId(new Designation(title), id);
    }

    public static Grade grade(long id, String name) {
        return withId(new Grade(name, new BigDecimal("800000"), new BigDecimal("1500000")), id);
    }

    /** An active employee with plausible reference data attached. */
    public static Employee employee(long id, String code, String firstName, String lastName) {
        Employee employee = new Employee(
                code,
                firstName,
                lastName,
                code.toLowerCase() + "@acme.test",
                LocalDate.of(2024, 4, 1),
                department(10L, "ENG", "Engineering"),
                designation(20L, "Software Engineer"),
                grade(30L, "G2"));
        return withId(employee, id);
    }

    public static <T extends BaseEntity> T withId(T entity, long id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not assign an id to " + entity.getClass(), e);
        }
    }
}
