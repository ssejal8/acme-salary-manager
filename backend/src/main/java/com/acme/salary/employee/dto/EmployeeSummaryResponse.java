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
        ReferenceResponse grade) {

    /**
     * Maps inside the read transaction, so the lazy reference associations — already
     * fetched by the repository's entity graph — resolve without a further query.
     */
    public static EmployeeSummaryResponse from(Employee employee) {
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
                new ReferenceResponse(employee.getGrade().getId(), employee.getGrade().getName()));
    }
}
