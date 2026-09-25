package com.acme.salary.orgdata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to add or retitle a designation (FR-3.2).
 *
 * <p>One record for both verbs, because a designation is only its title: there is no field
 * that can be set at creation and not afterwards, so two identical records would be two
 * names for the same shape.
 */
public record SaveDesignationRequest(
        @NotBlank @Size(max = 120) String title) {
}
