package com.acme.salary.salarycomponent.dto;

import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request to define a new salary component (FR-3.4).
 *
 * <p>Shape is checked here by Bean Validation; the rules that need context — the 100%
 * ceiling on a percentage component, code uniqueness — are enforced in the domain and the
 * service, because a client must not be the only thing standing between a typo and the
 * payroll engine.
 */
public record CreateSalaryComponentRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull ComponentType type,
        @NotNull CalculationType calculationType,
        @NotNull @DecimalMin("0.00") BigDecimal value,
        boolean taxable) {
}
