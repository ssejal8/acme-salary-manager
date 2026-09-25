package com.acme.salary.employee.dto;

import com.acme.salary.employee.Employee;
import com.acme.salary.employee.EmployeeStatus;
import java.time.LocalDate;

/**
 * One row of an employee list (FR-2.4).
 *
 * <p>A summary, not the whole entity: no audit timestamps, no linked user id, and nothing
 * about compensation. Salary figures are the most sensitive data in the system and have
 * no business appearing in a list payload (NFR-2.7).
 */
public record EmployeeSummaryResponse(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String fullName,
        String workEmail,
        LocalDate dateOfJoining,
        LocalDate exitDate,
        EmployeeStatus status,
        ReferenceResponse department,
        ReferenceResponse designation,
        ReferenceResponse grade,
        /**
         * The password for the login provisioned with this record (FR-2.7), present
         * <em>only</em> in the response to creating one — Jackson omits nulls, so it never
         * appears on a read.
         *
         * <p>Shown once and unrecoverable: it is stored only as a BCrypt hash (FR-1.2) and
         * is not logged or audited. It is here because an account nobody can sign in to is
         * not a provisioned account, and the alternative — a reset flow with email
         * delivery — is out of scope (requirements §2.4). The employee changes it with
         * {@code POST /auth/change-password} (FR-1.6).
         *
         * <p>Absent when an existing login was linked instead of created, because there is
         * no new credential to hand over.
         */
        String temporaryPassword) {

    /**
     * Maps inside the read transaction, so the lazy reference associations — already
     * fetched by the repository's entity graph — resolve without a further query.
     */
    public static EmployeeSummaryResponse from(Employee employee) {
        return from(employee, null);
    }

    /** The same, carrying a one-time password for a record that has just been created. */
    public static EmployeeSummaryResponse from(Employee employee, String temporaryPassword) {
        return new EmployeeSummaryResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.fullName(),
                employee.getWorkEmail(),
                employee.getDateOfJoining(),
                employee.getExitDate(),
                employee.getStatus(),
                new ReferenceResponse(employee.getDepartment().getId(), employee.getDepartment().getName()),
                new ReferenceResponse(employee.getDesignation().getId(), employee.getDesignation().getTitle()),
                new ReferenceResponse(employee.getGrade().getId(), employee.getGrade().getName()),
                temporaryPassword);
    }
}
