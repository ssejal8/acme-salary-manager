package com.acme.salary.salarystructure;

import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;

/**
 * One line of a compensation package as the calculator sees it: a definition plus the
 * figure configured for this employee.
 *
 * <p>A plain record rather than an entity, so {@link SalaryStructureCalculator} stays pure
 * and testable without a database — the same reason the payroll engine's core takes value
 * objects (architecture §4.2).
 *
 * @param value the monthly amount for a FLAT component, or the percentage for a
 *     PERCENT_OF_BASIC one
 */
public record ComponentAmount(
        Long componentId,
        String code,
        String name,
        ComponentType type,
        CalculationType calculationType,
        BigDecimal value) {

    public boolean isBasic() {
        return com.acme.salary.salarycomponent.SalaryComponent.BASIC_CODE.equals(code);
    }
}
