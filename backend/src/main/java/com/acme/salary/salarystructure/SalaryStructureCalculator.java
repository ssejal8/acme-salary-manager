package com.acme.salary.salarystructure;

import com.acme.salary.common.error.ApiError;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.money.Money;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarycomponent.SalaryComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out what a compensation package comes to. Pure: no Spring, no repository, no
 * clock, no I/O.
 *
 * <p>This is the same arithmetic a payslip performs at full attendance. When the payroll
 * engine arrives it prorates the earnings first (FR-5.4) and then follows exactly the
 * ordering below, which is why the rules live in one place rather than being restated
 * there:
 *
 * <ol>
 *   <li>resolve basic, the base for every percentage component;
 *   <li>compute and round each component individually;
 *   <li>sum the rounded lines into gross and deductions;
 *   <li>net is gross less deductions.
 * </ol>
 *
 * <p>Rounding before summation is deliberate (NFR-3.3): summing unrounded values and
 * rounding once would be marginally closer to the abstract arithmetic and visibly wrong on
 * paper, because the printed lines would not add up to the printed total.
 */
public final class SalaryStructureCalculator {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private SalaryStructureCalculator() {
    }

    /**
     * @param components the package's lines; must contain exactly one BASIC earning with a
     *     positive amount (FR-4.2)
     * @throws ValidationException if the package cannot produce a payable payslip
     */
    public static StructureTotals compute(List<ComponentAmount> components) {
        validate(components);
        BigDecimal basic = basicOf(components);

        List<ComputedComponent> earnings = new ArrayList<>();
        List<ComputedComponent> deductions = new ArrayList<>();
        for (ComponentAmount component : components) {
            ComputedComponent computed = new ComputedComponent(
                    component.componentId(),
                    component.code(),
                    component.name(),
                    component.type(),
                    component.calculationType(),
                    Money.normalize(component.value()),
                    amountOf(component, basic));
            if (component.type() == ComponentType.EARNING) {
                earnings.add(computed);
            } else {
                deductions.add(computed);
            }
        }

        BigDecimal gross = Money.sum(earnings.stream().map(ComputedComponent::amount).toList());
        BigDecimal totalDeductions = Money.sum(deductions.stream().map(ComputedComponent::amount).toList());
        BigDecimal net = gross.subtract(totalDeductions);

        if (Money.isNegative(net)) {
            throw ValidationException.field("components",
                    "deductions (%s) exceed gross pay (%s), which would leave a negative net salary"
                            .formatted(totalDeductions.toPlainString(), gross.toPlainString()));
        }

        return new StructureTotals(
                basic,
                gross,
                totalDeductions,
                Money.normalize(net),
                Money.normalize(gross.multiply(MONTHS_PER_YEAR)),
                List.copyOf(earnings),
                List.copyOf(deductions));
    }

    /**
     * A single component's rounded monthly amount.
     *
     * <p>A percentage is taken against the basic <em>as supplied</em>. In a payroll run
     * that basic has already been prorated for loss of pay, so provident-fund-style
     * deductions follow attendance down without this method knowing anything about
     * attendance (FR-5.4).
     */
    public static BigDecimal amountOf(ComponentAmount component, BigDecimal basic) {
        if (component.calculationType() == CalculationType.PERCENT_OF_BASIC) {
            return Money.percentOf(basic, component.value());
        }
        return Money.normalize(component.value());
    }

    private static BigDecimal basicOf(List<ComponentAmount> components) {
        return components.stream()
                .filter(ComponentAmount::isBasic)
                .map(component -> Money.normalize(component.value()))
                .findFirst()
                .orElseThrow(() -> ValidationException.field("components",
                        "a salary structure must include a " + SalaryComponent.BASIC_CODE + " component"));
    }

    private static void validate(List<ComponentAmount> components) {
        if (components == null || components.isEmpty()) {
            throw ValidationException.field("components", "at least one component is required");
        }

        List<ApiError.FieldError> problems = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        int basicCount = 0;

        for (ComponentAmount component : components) {
            if (!seenCodes.add(component.code())) {
                problems.add(new ApiError.FieldError("components",
                        "component " + component.code() + " appears more than once"));
            }
            BigDecimal value = Money.normalize(component.value());
            if (Money.isNegative(value)) {
                problems.add(new ApiError.FieldError("components",
                        "component " + component.code() + " must not be negative"));
            }
            if (component.calculationType() == CalculationType.PERCENT_OF_BASIC
                    && value.compareTo(BigDecimal.valueOf(100)) > 0) {
                problems.add(new ApiError.FieldError("components",
                        "component " + component.code() + " must not exceed 100 percent"));
            }
            if (component.isBasic()) {
                basicCount++;
                if (component.calculationType() != CalculationType.FLAT) {
                    problems.add(new ApiError.FieldError("components",
                            SalaryComponent.BASIC_CODE + " must be a flat amount, not a percentage of itself"));
                }
                if (component.type() != ComponentType.EARNING) {
                    problems.add(new ApiError.FieldError("components",
                            SalaryComponent.BASIC_CODE + " must be an earning"));
                }
                if (Money.isZero(value)) {
                    problems.add(new ApiError.FieldError("components",
                            SalaryComponent.BASIC_CODE + " must be greater than zero"));
                }
            }
        }

        if (basicCount == 0) {
            problems.add(new ApiError.FieldError("components",
                    "a salary structure must include a " + SalaryComponent.BASIC_CODE + " component"));
        }

        if (!problems.isEmpty()) {
            throw new ValidationException("Salary structure is not valid", problems);
        }
    }
}
