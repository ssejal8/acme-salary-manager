package com.acme.salary.orgdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to rename a department (FR-3.1).
 *
 * <p>Only the name. The code is immutable after creation — it is what reports and stored
 * filters refer to — so a wrong code is corrected by adding the right department rather
 * than by silently redefining an existing one.
 */
public record UpdateDepartmentRequest(
        @NotBlank @Size(max = 120) String name) {
}
