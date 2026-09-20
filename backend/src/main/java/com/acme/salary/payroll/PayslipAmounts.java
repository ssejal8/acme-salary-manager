package com.acme.salary.payroll;

import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;
import java.util.List;

/**
 * What one employee is owed for one period, as the calculator works it out.
 *
 * <p>A value object, so {@link PayslipCalculator} can be exercised without a database —
 * the same reason {@code ComponentAmount} exists on the structure side.
 *
 * @param proratedBasic the basic after proration, kept because it is the base every
 *     percentage deduction was computed against and is the first thing anyone checks when
 *     a figure looks wrong
 * @param lines every earning and deduction, in display order
 */
public record PayslipAmounts(
        int totalDays,
        int paidDays,
        int lopDays,
        BigDecimal proratedBasic,
        BigDecimal grossPay,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        List<Line> lines) {

    /**
     * One payslip line.
     *
     * <p>Carries the component's code, name and type as values rather than a reference to
     * the definition: a payslip must render exactly as published even if the component is
     * later renamed (ADR-010), which is why {@code payslip_lines} copies these columns
     * instead of joining.
     */
    public record Line(
            String code,
            String name,
            ComponentType type,
            BigDecimal amount,
            int sortOrder) {

        public boolean isEarning() {
            return type == ComponentType.EARNING;
        }
    }

    public List<Line> earnings() {
        return lines.stream().filter(Line::isEarning).toList();
    }

    public List<Line> deductions() {
        return lines.stream().filter(line -> !line.isEarning()).toList();
    }
}
