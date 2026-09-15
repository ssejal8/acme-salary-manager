package com.acme.salary.employee;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the predicate for an employee list query.
 *
 * <p>Filtering happens in the database, never in memory (NFR-1.2): these specifications
 * are combined with the {@code Pageable} so PostgreSQL does the work and the page count
 * is real.
 */
public final class EmployeeSpecifications {

    private static final String FIRST_NAME = "firstName";
    private static final String LAST_NAME = "lastName";
    private static final String STATUS = "status";
    private static final String DEPARTMENT = "department";
    private static final String DESIGNATION = "designation";
    private static final String GRADE = "grade";

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> matching(EmployeeSearch search) {
        List<Specification<Employee>> specifications = new ArrayList<>();
        specifications.add(statusIn(search.statusFilter()));
        if (search.nameQuery() != null) {
            specifications.add(nameContains(search.nameQuery()));
        }
        if (search.departmentId() != null) {
            specifications.add(referenceEquals(DEPARTMENT, search.departmentId()));
        }
        if (search.designationId() != null) {
            specifications.add(referenceEquals(DESIGNATION, search.designationId()));
        }
        if (search.gradeId() != null) {
            specifications.add(referenceEquals(GRADE, search.gradeId()));
        }
        return specifications.stream().reduce(alwaysTrue(), Specification::and);
    }

    /** Case-insensitive substring match over the employee's full name. */
    public static Specification<Employee> nameContains(String nameQuery) {
        String pattern = "%" + nameQuery.strip().toLowerCase() + "%";
        return (root, query, builder) -> {
            var fullName = builder.lower(builder.concat(
                    builder.concat(root.get(FIRST_NAME), " "), root.get(LAST_NAME)));
            return builder.like(fullName, pattern);
        };
    }

    public static Specification<Employee> statusIn(EmployeeSearch.StatusFilter filter) {
        return switch (filter) {
            case ACTIVE_ONLY -> hasStatus(EmployeeStatus.ACTIVE);
            case INACTIVE_ONLY -> hasStatus(EmployeeStatus.INACTIVE);
            case ALL -> alwaysTrue();
        };
    }

    public static Specification<Employee> hasStatus(EmployeeStatus status) {
        return (root, query, builder) -> builder.equal(root.get(STATUS), status);
    }

    private static Specification<Employee> referenceEquals(String association, Long id) {
        return (root, query, builder) -> builder.equal(root.get(association).get("id"), id);
    }

    private static Specification<Employee> alwaysTrue() {
        return (root, query, builder) -> builder.conjunction();
    }
}
