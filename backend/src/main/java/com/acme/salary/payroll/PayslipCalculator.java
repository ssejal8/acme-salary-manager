package com.acme.salary.payroll;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.money.Money;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarycomponent.SalaryComponent;
import com.acme.salary.salarystructure.ComponentAmount;
import com.acme.salary.salarystructure.SalaryStructureCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out one payslip from one salary structure and one attendance figure (FR-5.3,
 * FR-5.4). Pure: no Spring, no repository, no clock, no I/O.
 *
 * <p>This is where the money is computed, so it carries the project's strictest coverage
 * bar. It is deliberately small, and deliberately does not restate the rules that already
 * live in {@link SalaryStructureCalculator} — percentage-of-basic resolution and
 * per-component rounding are delegated, not copied. Two implementations of money
 * arithmetic is exactly what ADR-006 exists to prevent.
 *
 * <h2>What proration does, and does not, apply to</h2>
 *
 * FR-5.4 says earnings are prorated by paid days over total days, and that
 * {@code PERCENT_OF_BASIC} deductions are computed against the <em>prorated</em> basic. It
 * says nothing about flat deductions; architecture §5.2 resolves that silence with
 * {@code FLAT → value as-is}, and this implements that pipeline step for step:
 *
 * <table border="1">
 *   <caption>Treatment by type and calculation</caption>
 *   <tr><th></th><th>{@code FLAT}</th><th>{@code PERCENT_OF_BASIC}</th></tr>
 *   <tr><td>Earning</td><td>prorated</td><td>percentage of the prorated basic</td></tr>
 *   <tr><td>Deduction</td><td><b>not</b> prorated</td><td>percentage of the prorated basic</td></tr>
 * </table>
 *
 * <p>A flat deduction — professional tax, say — is a fixed statutory charge that does not
 * shrink because someone took unpaid leave, which is both the conventional treatment and
 * the literal reading of the requirement. A percentage deduction needs no proration of its
 * own: taking 12% of an already-prorated basic follows attendance down exactly once.
 * Prorating it again would halve it twice.
 *
 * <p>The ordering matters and is the same as the structure calculator's: prorate, then
 * resolve percentages against the prorated basic, then round each line, then sum. Rounding
 * before summation (NFR-3.3) is what makes the printed lines add up to the printed total.
 */
public final class PayslipCalculator {

    private PayslipCalculator() {
    }

    /**
     * @param components the governing revision's lines; must contain a positive BASIC
     *     earning, which {@link SalaryStructureCalculator} guarantees for anything that
     *     was assigned through the API
     * @param lopDays days of loss of pay in the period, 0 to {@code totalDays}
     * @param totalDays calendar days in the period, from {@link PayrollPeriod#totalDays()}
     * @throws ValidationException if the day counts are not a sane pair, or the package
     *     cannot produce a payable payslip
     */
    public static PayslipAmounts compute(List<ComponentAmount> components, int lopDays, int totalDays) {
        if (totalDays <= 0) {
            throw ValidationException.field("totalDays", "must be positive");
        }
        if (lopDays < 0 || lopDays > totalDays) {
            throw ValidationException.field(
                    "lopDays", "must be between 0 and %d for this period".formatted(totalDays));
        }
        if (components == null || components.isEmpty()) {
            throw ValidationException.field("components", "at least one component is required");
        }

        int paidDays = totalDays - lopDays;
        BigDecimal proratedBasic = Money.prorate(basicOf(components), paidDays, totalDays);

        List<PayslipAmounts.Line> lines = new ArrayList<>();
        BigDecimal gross = Money.ZERO;
        BigDecimal deductions = Money.ZERO;
        int sortOrder = 0;

        for (ComponentAmount component : components) {
            // Delegating to the structure calculator keeps one implementation of the
            // percent-of-basic rule and of per-component rounding. The only thing changed
            // on the way in is the value of a flat earning, which proration acts on.
            BigDecimal amount = SalaryStructureCalculator.amountOf(
                    proratedFor(component, paidDays, totalDays), proratedBasic);

            lines.add(new PayslipAmounts.Line(
                    component.code(), component.name(), component.type(), amount, sortOrder++));

            if (component.type() == ComponentType.EARNING) {
                gross = gross.add(amount);
            } else {
                deductions = deductions.add(amount);
            }
        }

        gross = Money.normalize(gross);
        deductions = Money.normalize(deductions);
        BigDecimal net = Money.normalize(gross.subtract(deductions));

        if (Money.isNegative(net)) {
            // The same guard the structure calculator applies, and it can still bite here
            // even on a package that was valid at full attendance: proration shrinks the
            // earnings while a flat deduction stays put.
            throw ValidationException.field("components",
                    ("deductions (%s) exceed prorated gross pay (%s) at %d of %d paid days, "
                            + "which would leave a negative net salary")
                            .formatted(deductions.toPlainString(), gross.toPlainString(), paidDays, totalDays));
        }

        return new PayslipAmounts(
                totalDays, paidDays, lopDays, proratedBasic, gross, deductions, net, List.copyOf(lines));
    }

    /**
     * The component as the calculation should see it.
     *
     * <p>Only a flat earning is rewritten: its configured monthly figure becomes the
     * prorated one. A percentage component is handed through untouched, because the
     * proration it needs has already happened to the basic it will be taken against — and
     * a flat deduction is not prorated at all.
     */
    private static ComponentAmount proratedFor(ComponentAmount component, int paidDays, int totalDays) {
        boolean proratable = component.type() == ComponentType.EARNING
                && component.calculationType() == CalculationType.FLAT;
        if (!proratable) {
            return component;
        }
        return new ComponentAmount(
                component.componentId(),
                component.code(),
                component.name(),
                component.type(),
                component.calculationType(),
                Money.prorate(component.value(), paidDays, totalDays));
    }

    private static BigDecimal basicOf(List<ComponentAmount> components) {
        return components.stream()
                .filter(ComponentAmount::isBasic)
                .map(component -> Money.normalize(component.value()))
                .findFirst()
                .orElseThrow(() -> ValidationException.field("components",
                        "the governing salary structure has no "
                                + SalaryComponent.BASIC_CODE + " component"));
    }
}
