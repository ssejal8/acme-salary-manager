package com.acme.salary.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to update an employee's editable fields (FR-2.3).
 *
 * <p>Two fields are missing from this record, and their absence is the contract: the
 * employee code appears on published payslips, and the date of joining is what every
 * effective-dated salary structure is validated against. Both are immutable after
 * creation, and the entity maps them {@code updatable = false} so the mapping enforces it
 * as well — a client sending them gets them ignored rather than silently applied.
 *
 * <p>The exit date is missing for a different reason: leaving is a transition, not an
 * edit, so it has its own endpoint (FR-2.5).
 *
 * <p>Every field is required, because this is a whole-resource replacement of the
 * editable set rather than a patch. A form that loaded the record and submits it back
 * sends all of them, and "absent means unchanged" is a semantics that invites a partial
 * update to clear a field by accident.
 */
public record UpdateEmployeeRequest(
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @NotBlank @Size(max = 255) @Email String workEmail,
        @NotNull Long departmentId,
        @NotNull Long designationId,
        @NotNull Long gradeId) {
}
