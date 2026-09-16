package com.acme.salary.salarycomponent.dto;

import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarycomponent.SalaryComponent;
import java.math.BigDecimal;

/**
 * A component definition as the API exposes it.
 *
 * @param value the monthly amount for a FLAT component, or the percentage for a
 *     PERCENT_OF_BASIC one — sent as a string, like every other amount (ADR-006)
 */
public record SalaryComponentResponse(
        Long id,
        String code,
        String name,
        ComponentType type,
        CalculationType calculationType,
        BigDecimal value,
        boolean taxable,
        boolean active) {

    public static SalaryComponentResponse from(SalaryComponent component) {
        return new SalaryComponentResponse(
                component.getId(),
                component.getCode(),
                component.getName(),
                component.getType(),
                component.getCalculationType(),
                component.getDefaultValue(),
                component.isTaxable(),
                component.isActive());
    }
}
