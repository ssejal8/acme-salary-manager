package com.acme.salary.report;

import com.acme.salary.common.money.Money;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Summary statistics for a group of employees' current compensation.
 *
 * <p>Pure: built from a list of {@link EmployeeCompensation} and nothing else, so every
 * figure here is unit-testable without a database (architecture §4.2).
 *
 * <p>Both a headcount and a priced headcount are reported because they differ whenever
 * someone has no package yet. Every average and percentile is over the <em>priced</em>
 * group; dividing by total headcount would report a number that is neither the cost per
 * employee nor the cost per paid employee.
 *
 * @param headcount employees in the group, whether or not they hold a package
 * @param employeesWithPackage how many of them have a current package
 * @param employeesWithoutPackage the remainder — a data gap, and a payroll run would skip them
 * @param totalMonthlyGross sum of each employee's rounded gross
 * @param medianMonthlyGross the middle gross, or the mean of the middle two for an even
 *     count; reported alongside the average because a few senior packages skew a mean
 */
public record CompensationMetrics(
        int headcount,
        int employeesWithPackage,
        int employeesWithoutPackage,
        BigDecimal totalMonthlyGross,
        BigDecimal totalMonthlyDeductions,
        BigDecimal totalMonthlyNet,
        BigDecimal totalAnnualCtc,
        BigDecimal averageMonthlyGross,
        BigDecimal medianMonthlyGross,
        BigDecimal lowestMonthlyGross,
        BigDecimal highestMonthlyGross) {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    public static CompensationMetrics of(int headcount, List<EmployeeCompensation> priced) {
        if (priced.isEmpty()) {
            return new CompensationMetrics(
                    headcount, 0, headcount,
                    Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                    Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);
        }

        List<BigDecimal> grossAscending = priced.stream()
                .map(EmployeeCompensation::monthlyGross)
                .sorted(Comparator.naturalOrder())
                .toList();

        BigDecimal totalGross = Money.sum(grossAscending);

        return new CompensationMetrics(
                headcount,
                priced.size(),
                Math.max(headcount - priced.size(), 0),
                totalGross,
                Money.sum(priced.stream().map(EmployeeCompensation::monthlyDeductions).toList()),
                Money.sum(priced.stream().map(EmployeeCompensation::monthlyNet).toList()),
                Money.sum(priced.stream().map(EmployeeCompensation::annualCtc).toList()),
                average(totalGross, priced.size()),
                median(grossAscending),
                grossAscending.get(0),
                grossAscending.get(grossAscending.size() - 1));
    }

    private static BigDecimal average(BigDecimal total, int count) {
        return total.divide(BigDecimal.valueOf(count), Money.SCALE, Money.ROUNDING);
    }

    private static BigDecimal median(List<BigDecimal> ascending) {
        int size = ascending.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return ascending.get(middle);
        }
        return ascending.get(middle - 1)
                .add(ascending.get(middle))
                .divide(TWO, Money.SCALE, Money.ROUNDING);
    }

    /** This group's share of an organisation-wide gross, to two decimal places. */
    public BigDecimal shareOf(BigDecimal organisationMonthlyGross) {
        if (organisationMonthlyGross == null || Money.isZero(organisationMonthlyGross)) {
            return Money.ZERO;
        }
        return totalMonthlyGross
                .multiply(BigDecimal.valueOf(100))
                .divide(organisationMonthlyGross, Money.SCALE, Money.ROUNDING);
    }
}
