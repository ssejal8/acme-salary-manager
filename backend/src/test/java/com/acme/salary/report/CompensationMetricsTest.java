package com.acme.salary.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.salary.common.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The statistics themselves: pure, and the part a reviewer will check by hand. */
class CompensationMetricsTest {

    private static EmployeeCompensation employee(long id, String gross, String deductions) {
        BigDecimal grossAmount = Money.of(gross);
        BigDecimal deductionAmount = Money.of(deductions);
        return new EmployeeCompensation(
                id, 10L, "Engineering", 30L, "G2",
                grossAmount,
                deductionAmount,
                grossAmount.subtract(deductionAmount),
                Money.normalize(grossAmount.multiply(BigDecimal.valueOf(12))));
    }

    @Nested
    @DisplayName("totals")
    class Totals {

        @Test
        void sumEachEmployeesAlreadyRoundedFigures() {
            CompensationMetrics metrics = CompensationMetrics.of(3, List.of(
                    employee(1L, "70000", "6000"),
                    employee(2L, "50000", "4000"),
                    employee(3L, "30000.33", "2000.33")));

            assertThat(metrics.totalMonthlyGross()).isEqualByComparingTo("150000.33");
            assertThat(metrics.totalMonthlyDeductions()).isEqualByComparingTo("12000.33");
            assertThat(metrics.totalMonthlyNet()).isEqualByComparingTo("138000.00");
            assertThat(metrics.totalAnnualCtc()).isEqualByComparingTo("1800003.96");
        }

        @Test
        void areZeroRatherThanNullForAnEmptyGroup() {
            CompensationMetrics metrics = CompensationMetrics.of(0, List.of());

            assertThat(metrics.totalMonthlyGross()).isEqualByComparingTo("0.00");
            assertThat(metrics.averageMonthlyGross()).isEqualByComparingTo("0.00");
            assertThat(metrics.medianMonthlyGross()).isEqualByComparingTo("0.00");
            assertThat(metrics.lowestMonthlyGross()).isEqualByComparingTo("0.00");
            assertThat(metrics.highestMonthlyGross()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("headcount versus priced headcount")
    class Coverage {

        @Test
        void countsEmployeesWithoutAPackageAsAGap() {
            // Five active people, three priced: the other two are a data gap a payroll
            // run would silently skip (FR-5.2).
            CompensationMetrics metrics = CompensationMetrics.of(5, List.of(
                    employee(1L, "70000", "0"),
                    employee(2L, "50000", "0"),
                    employee(3L, "30000", "0")));

            assertThat(metrics.headcount()).isEqualTo(5);
            assertThat(metrics.employeesWithPackage()).isEqualTo(3);
            assertThat(metrics.employeesWithoutPackage()).isEqualTo(2);
        }

        @Test
        void averagesOverPricedEmployeesNotTotalHeadcount() {
            // Dividing 150,000 by five would report 30,000 — a figure that is neither the
            // cost per employee nor what anyone is actually paid.
            CompensationMetrics metrics = CompensationMetrics.of(5, List.of(
                    employee(1L, "70000", "0"),
                    employee(2L, "50000", "0"),
                    employee(3L, "30000", "0")));

            assertThat(metrics.averageMonthlyGross()).isEqualByComparingTo("50000.00");
        }

        @Test
        void neverReportsANegativeGapIfMoreArePricedThanCounted() {
            // Defensive: a caller passing an inconsistent headcount gets zero, not -1.
            CompensationMetrics metrics = CompensationMetrics.of(1, List.of(
                    employee(1L, "70000", "0"),
                    employee(2L, "50000", "0")));

            assertThat(metrics.employeesWithoutPackage()).isZero();
        }
    }

    @Nested
    @DisplayName("distribution")
    class Distribution {

        @Test
        void medianOfAnOddCountIsTheMiddleValue() {
            CompensationMetrics metrics = CompensationMetrics.of(3, List.of(
                    employee(1L, "30000", "0"),
                    employee(2L, "90000", "0"),
                    employee(3L, "50000", "0")));

            assertThat(metrics.medianMonthlyGross()).isEqualByComparingTo("50000.00");
        }

        @Test
        void medianOfAnEvenCountIsTheMeanOfTheMiddleTwo() {
            CompensationMetrics metrics = CompensationMetrics.of(4, List.of(
                    employee(1L, "30000", "0"),
                    employee(2L, "50000", "0"),
                    employee(3L, "60000", "0"),
                    employee(4L, "90000", "0")));

            assertThat(metrics.medianMonthlyGross()).isEqualByComparingTo("55000.00");
        }

        @Test
        void medianIsUnaffectedByInputOrder() {
            List<EmployeeCompensation> ascending = List.of(
                    employee(1L, "30000", "0"), employee(2L, "50000", "0"), employee(3L, "90000", "0"));
            List<EmployeeCompensation> descending = List.of(
                    employee(3L, "90000", "0"), employee(2L, "50000", "0"), employee(1L, "30000", "0"));

            assertThat(CompensationMetrics.of(3, ascending).medianMonthlyGross())
                    .isEqualByComparingTo(CompensationMetrics.of(3, descending).medianMonthlyGross());
        }

        @Test
        void medianResistsTheSkewThatMovesTheMean() {
            // One founder-sized package: the mean jumps, the median barely moves. That is
            // the whole reason both are reported.
            List<EmployeeCompensation> withOutlier = List.of(
                    employee(1L, "30000", "0"),
                    employee(2L, "40000", "0"),
                    employee(3L, "50000", "0"),
                    employee(4L, "5000000", "0"));
            CompensationMetrics metrics = CompensationMetrics.of(4, withOutlier);

            assertThat(metrics.averageMonthlyGross()).isEqualByComparingTo("1280000.00");
            assertThat(metrics.medianMonthlyGross()).isEqualByComparingTo("45000.00");
        }

        @Test
        void reportsTheLowestAndHighestGross() {
            CompensationMetrics metrics = CompensationMetrics.of(3, List.of(
                    employee(1L, "50000", "0"),
                    employee(2L, "30000", "0"),
                    employee(3L, "90000", "0")));

            assertThat(metrics.lowestMonthlyGross()).isEqualByComparingTo("30000.00");
            assertThat(metrics.highestMonthlyGross()).isEqualByComparingTo("90000.00");
        }

        @Test
        void handlesASingleEmployeeWhereEveryStatisticIsTheSameFigure() {
            CompensationMetrics metrics = CompensationMetrics.of(1, List.of(employee(1L, "70000", "6000")));

            assertThat(metrics.averageMonthlyGross()).isEqualByComparingTo("70000.00");
            assertThat(metrics.medianMonthlyGross()).isEqualByComparingTo("70000.00");
            assertThat(metrics.lowestMonthlyGross()).isEqualByComparingTo("70000.00");
            assertThat(metrics.highestMonthlyGross()).isEqualByComparingTo("70000.00");
        }

        @Test
        void averageRoundsHalfUpToTwoDecimals() {
            // 100,000 over three people does not divide cleanly.
            CompensationMetrics metrics = CompensationMetrics.of(3, List.of(
                    employee(1L, "33333.33", "0"),
                    employee(2L, "33333.33", "0"),
                    employee(3L, "33333.34", "0")));

            assertThat(metrics.averageMonthlyGross()).isEqualByComparingTo("33333.33");
            assertThat(metrics.averageMonthlyGross().scale()).isEqualTo(Money.SCALE);
        }
    }

    @Nested
    @DisplayName("share of organisation cost")
    class Share {

        @Test
        void isThePercentageOfOrganisationGross() {
            CompensationMetrics metrics = CompensationMetrics.of(1, List.of(employee(1L, "25000", "0")));

            assertThat(metrics.shareOf(Money.of("100000"))).isEqualByComparingTo("25.00");
        }

        @Test
        void roundsToTwoDecimals() {
            CompensationMetrics metrics = CompensationMetrics.of(1, List.of(employee(1L, "33333.33", "0")));

            assertThat(metrics.shareOf(Money.of("100000"))).isEqualByComparingTo("33.33");
        }

        @Test
        void isZeroRatherThanAnArithmeticErrorWhenTheOrganisationCostsNothing() {
            // An organisation where nobody has a package yet: no division by zero.
            CompensationMetrics metrics = CompensationMetrics.of(2, List.of());

            assertThat(metrics.shareOf(Money.ZERO)).isEqualByComparingTo("0.00");
            assertThat(metrics.shareOf(null)).isEqualByComparingTo("0.00");
        }

        @Test
        void sharesAcrossDisjointGroupsAddUpToOneHundred() {
            CompensationMetrics engineering = CompensationMetrics.of(1, List.of(employee(1L, "60000", "0")));
            CompensationMetrics finance = CompensationMetrics.of(1, List.of(employee(2L, "40000", "0")));
            BigDecimal organisation = Money.of("100000");

            assertThat(engineering.shareOf(organisation).add(finance.shareOf(organisation)))
                    .isEqualByComparingTo("100.00");
        }
    }
}
