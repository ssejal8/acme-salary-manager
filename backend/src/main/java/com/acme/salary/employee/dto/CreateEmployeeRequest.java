package com.acme.salary.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request to create an employee record (FR-2.1).
 *
 * <p>Shape only. The rules that need context are elsewhere on purpose: uniqueness of the
 * code and email needs the database and so lives in the service (FR-2.2), and the
 * normalising rules — code uppercased, email lowercased — live in the {@code Employee}
 * entity, which is the authority on what a valid employee is however it was constructed.
 *
 * <p>{@code dateOfJoining} is deliberately <em>not</em> constrained to the past. A new
 * hire who has accepted an offer and starts next month is an employee HR needs to record
 * now, and their first payroll run will simply not include them (FR-5.2).
 *
 * <p>Status is absent: a new record is always ACTIVE. Leaving is a transition with an exit
 * date attached, not a field a client sets (FR-2.5).
 */
public record CreateEmployeeRequest(
        @NotBlank @Size(max = 20) String employeeCode,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        // @Email is lenient — it accepts "a@b" — so the entity's stricter check stays the
        // authority. This annotation is here to make the common typo a 400 naming the
        // field rather than a message from deeper down.
        @NotBlank @Size(max = 255) @Email String workEmail,
        @NotNull LocalDate dateOfJoining,
        @NotNull Long departmentId,
        @NotNull Long designationId,
        @NotNull Long gradeId) {
}
