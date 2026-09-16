package com.acme.salary.salarystructure.dto;

import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarystructure.ComputedComponent;
import java.math.BigDecimal;

/**
 * One line of a package, showing both what was configured and what it comes to.
 *
 * <p>Both figures are present because for a percentage component they differ, and a
 * reviewer needs to see the 12% as well as the ₹6,000 it produces.
 */
public record SalaryStructureComponentResponse(
        Long componentId,
        String code,
        String name,
        ComponentType type,
        CalculationType calculationType,
        BigDecimal configuredValue,
        BigDecimal monthlyAmount) {

    public static SalaryStructureComponentResponse from(ComputedComponent computed) {
        return new SalaryStructureComponentResponse(
                computed.componentId(),
                computed.code(),
                computed.name(),
                computed.type(),
                computed.calculationType(),
                computed.configuredValue(),
                computed.amount());
    }
}
