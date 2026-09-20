package com.acme.salary.payroll.dto;

import com.acme.salary.payroll.PayslipLine;
import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;

/**
 * One line of a payslip (FR-6.2).
 *
 * <p>The code and name come from the payslip's own columns rather than from the component
 * definition, so a published payslip reads the same after a component is renamed
 * (ADR-010).
 */
public record PayslipLineResponse(
        String code,
        String name,
        ComponentType type,
        BigDecimal amount) {

    public static PayslipLineResponse from(PayslipLine line) {
        return new PayslipLineResponse(
                line.getComponentCode(), line.getComponentName(), line.getType(), line.getAmount());
    }
}
