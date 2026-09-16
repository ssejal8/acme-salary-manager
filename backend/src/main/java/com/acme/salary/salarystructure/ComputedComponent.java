package com.acme.salary.salarystructure;

import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;

/**
 * A component after its amount has been worked out and rounded.
 *
 * @param amount the rounded monthly figure — rounding happens per component, before any
 *     summation, so displayed lines always add up to displayed totals (NFR-3.3)
 */
public record ComputedComponent(
        Long componentId,
        String code,
        String name,
        ComponentType type,
        CalculationType calculationType,
        BigDecimal configuredValue,
        BigDecimal amount) {
}
