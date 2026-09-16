package com.acme.salary.salarystructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.money.Money;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariants that must hold for <em>every</em> compensation package, checked against a
 * large sample of randomly generated ones rather than hand-picked cases.
 *
 * <p>The worked examples elsewhere prove the arithmetic is right for the packages someone
 * thought to write down. These properties are what catch the package nobody thought of:
 * an awkward percentage, a value that rounds against you, forty components instead of
 * three. The seed is fixed, so a failure is reproducible rather than a flaky annoyance.
 */
class SalaryStructureCalculatorInvariantTest {

    private static final int SAMPLE_SIZE = 2_000;
    private static final long SEED = 20260917L;

    @Test
    @DisplayName("printed lines always add up to printed totals")
    void linesAlwaysSumToTheTotals() {
        // NFR-3.3. This is the property that makes a payslip checkable by hand, and the
        // reason rounding happens per component rather than once at the end.
        forEachGeneratedPackage((components, totals) -> {
            BigDecimal earningSum = totals.earnings().stream()
                    .map(ComputedComponent::amount)
                    .reduce(Money.ZERO, BigDecimal::add);
            BigDecimal deductionSum = totals.deductions().stream()
                    .map(ComputedComponent::amount)
                    .reduce(Money.ZERO, BigDecimal::add);

            assertThat(totals.grossMonthly()).isEqualByComparingTo(earningSum);
            assertThat(totals.totalDeductions()).isEqualByComparingTo(deductionSum);
        });
    }

    @Test
    @DisplayName("net is always gross less deductions, and never negative")
    void netIsAlwaysGrossLessDeductions() {
        forEachGeneratedPackage((components, totals) ->
                assertThat(totals.netMonthly())
                        .isEqualByComparingTo(totals.grossMonthly().subtract(totals.totalDeductions()))
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("annual CTC is always twelve times gross")
    void annualCtcIsTwelveTimesGross() {
        forEachGeneratedPackage((components, totals) ->
                assertThat(totals.annualCtc())
                        .isEqualByComparingTo(totals.grossMonthly().multiply(BigDecimal.valueOf(12))));
    }

    @Test
    @DisplayName("every figure is stored at exactly two decimal places")
    void everyFigureCarriesMoneyScale() {
        // A figure at another scale would serialise inconsistently and could round twice.
        forEachGeneratedPackage((components, totals) -> {
            assertThat(totals.basic().scale()).isEqualTo(Money.SCALE);
            assertThat(totals.grossMonthly().scale()).isEqualTo(Money.SCALE);
            assertThat(totals.totalDeductions().scale()).isEqualTo(Money.SCALE);
            assertThat(totals.netMonthly().scale()).isEqualTo(Money.SCALE);
            assertThat(totals.annualCtc().scale()).isEqualTo(Money.SCALE);
            totals.earnings().forEach(line -> assertThat(line.amount().scale()).isEqualTo(Money.SCALE));
            totals.deductions().forEach(line -> assertThat(line.amount().scale()).isEqualTo(Money.SCALE));
        });
    }

    @Test
    @DisplayName("computing the same package twice gives the same answer")
    void computationIsDeterministic() {
        // Recomputing a draft payroll run must not move the figures (FR-5.6).
        Random random = new Random(SEED);
        for (int i = 0; i < 200; i++) {
            List<ComponentAmount> components = generatePackage(random);
            try {
                StructureTotals first = SalaryStructureCalculator.compute(components);
                StructureTotals second = SalaryStructureCalculator.compute(components);
                assertThat(second).isEqualTo(first);
            } catch (ValidationException rejected) {
                // Rejection must be deterministic too.
                assertThat(catchValidation(components)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("gross never drops below basic")
    void grossIsNeverLessThanBasic() {
        // Basic is an earning, and earnings only add, so this must hold whatever else the
        // package contains.
        forEachGeneratedPackage((components, totals) ->
                assertThat(totals.grossMonthly()).isGreaterThanOrEqualTo(totals.basic()));
    }

    @Test
    void theSampleActuallyExercisesTheCalculator() {
        // Guards every property above from passing because nothing was generated.
        Random random = new Random(SEED);
        int accepted = 0;
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            try {
                SalaryStructureCalculator.compute(generatePackage(random));
                accepted++;
            } catch (ValidationException ignored) {
                // Deductions exceeding gross is a legitimate rejection, not a gap.
            }
        }
        assertThat(accepted).isGreaterThan(SAMPLE_SIZE / 2);
    }

    private interface PackageAssertion {
        void check(List<ComponentAmount> components, StructureTotals totals);
    }

    private void forEachGeneratedPackage(PackageAssertion assertion) {
        Random random = new Random(SEED);
        int checked = 0;
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            List<ComponentAmount> components = generatePackage(random);
            StructureTotals totals;
            try {
                totals = SalaryStructureCalculator.compute(components);
            } catch (ValidationException rejected) {
                continue;
            }
            assertion.check(components, totals);
            checked++;
        }
        assertThat(checked).isPositive();
    }

    private static boolean catchValidation(List<ComponentAmount> components) {
        try {
            SalaryStructureCalculator.compute(components);
            return false;
        } catch (ValidationException e) {
            return true;
        }
    }

    /**
     * Builds a package with a positive basic and between zero and eleven further
     * components, mixing flat and percentage calculations, earnings and deductions, and
     * values chosen to land on awkward fractions of a paise.
     */
    private static List<ComponentAmount> generatePackage(Random random) {
        List<ComponentAmount> components = new ArrayList<>();
        components.add(new ComponentAmount(1L, "BASIC", "Basic", ComponentType.EARNING,
                CalculationType.FLAT, randomAmount(random, 1_000, 500_000)));

        int extras = random.nextInt(12);
        for (int i = 0; i < extras; i++) {
            boolean earning = random.nextBoolean();
            boolean percentage = random.nextBoolean();
            components.add(new ComponentAmount(
                    (long) i + 2,
                    "C" + i,
                    "Component " + i,
                    earning ? ComponentType.EARNING : ComponentType.DEDUCTION,
                    percentage ? CalculationType.PERCENT_OF_BASIC : CalculationType.FLAT,
                    percentage ? randomPercentage(random) : randomAmount(random, 0, 60_000)));
        }
        return components;
    }

    /** A value with three decimal places, so rounding is genuinely exercised. */
    private static BigDecimal randomAmount(Random random, int minRupees, int maxRupees) {
        int rupees = minRupees + random.nextInt(Math.max(1, maxRupees - minRupees));
        int thousandths = random.nextInt(1_000);
        return new BigDecimal(rupees + "." + String.format("%03d", thousandths));
    }

    private static BigDecimal randomPercentage(Random random) {
        return new BigDecimal(random.nextInt(4_001)).divide(BigDecimal.valueOf(100));
    }
}
