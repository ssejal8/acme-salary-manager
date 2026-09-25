package com.acme.salary.orgdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to add a department (FR-3.1).
 *
 * <p>The code is part of this request and not of the update: it appears in reports and in
 * saved filters, so renaming a department is an edit while re-coding one is a different
 * department. Uniqueness of both is checked in the service, where the database is.
 */
public record CreateDepartmentRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name) {
}
