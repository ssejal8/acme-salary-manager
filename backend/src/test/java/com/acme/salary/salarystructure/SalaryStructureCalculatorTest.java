package com.acme.salary.salarystructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The compensation arithmetic carries the strictest test bar in the codebase (NFR-5.3):
 * the payroll engine will inherit this code, and a rounding mistake here becomes a wrong
 * payslip for everyone.
 */
class SalaryStructureCalculatorTest {

    private static ComponentAmount earning(String code, String value) {
        return new ComponentAmount(1L, code, code, ComponentType.EARNING, CalculationType.FLAT, new BigDecimal(value));
    }

    private static ComponentAmount deduction(String code, String value) {
        return new ComponentAmount(2L, code, code, ComponentType.DEDUCTION, CalculationType.FLAT, new BigDecimal(value));
    }

    private static ComponentAmount percentDeduction(String code, String percent) {
        return new ComponentAmount(3L, code, code, ComponentType.DEDUCTION,
                CalculationType.PERCENT_OF_BASIC, new BigDecimal(percent));
    }

    private static ComponentAmount percentEarning(String code, String percent) {
        return new ComponentAmount(4L, code, code, ComponentType.EARNING,
                CalculationType.PERCENT_OF_BASIC, new BigDecimal(percent));
    }

    @Nested
    @DisplayName("the acceptance scenario")
    class AcceptanceScenario {

        /** Requirements §7 scenario 1: Basic 50,000 + HRA 20,000 − PF 6,000. */
        private final List<ComponentAmount> package1 = List.of(
                earning("BASIC", "50000"),
                earning("HRA", "20000"),
                deduction("PF", "6000"));

        @Test
        void computesGrossDeductionsAndNet() {
            StructureTotals totals = SalaryStructureCalculator.compute(package1);

            assertThat(totals.grossMonthly()).isEqualByComparingTo("70000.00");
            assertThat(totals.totalDeductions()).isEqualByComparingTo("6000.00");
            assertThat(totals.netMonthly()).isEqualByComparingTo("64000.00");
        }

        @Test
        void annualisesGrossIntoCtc() {
            // CTC is the annualised total of employer-borne earnings (requirements §1.3),
            // so it follows gross, not net.
            assertThat(SalaryStructureCalculator.compute(package1).annualCtc())
                    .isEqualByComparingTo("840000.00");
        }

        @Test
        void separatesEarningsFromDeductions() {
            StructureTotals totals = SalaryStructureCalculator.compute(package1);

            assertThat(totals.earnings()).extracting(ComputedComponent::code)
                    .containsExactly("BASIC", "HRA");
            assertThat(totals.deductions()).extracting(ComputedComponent::code).containsExactly("PF");
        }
    }

    @Nested
    @DisplayName("percentage components")
    class PercentageComponents {

        @Test
        void areComputedAgainstBasicNotGross() {
            // 12% of the 50,000 basic, not of the 70,000 gross.
            StructureTotals totals = SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "50000"),
                    earning("HRA", "20000"),
                    percentDeduction("PF", "12")));

            assertThat(totals.deductions()).singleElement()
                    .extracting(ComputedComponent::amount)
                    .isEqualTo(new BigDecimal("6000.00"));
            assertThat(totals.netMonthly()).isEqualByComparingTo("64000.00");
        }

        @Test
        void workAsEarningsToo() {
            StructureTotals totals = SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "50000"),
                    percentEarning("HRA", "40")));

            assertThat(totals.grossMonthly()).isEqualByComparingTo("70000.00");
        }

        @Test
        void keepTheConfiguredPercentageAlongsideTheComputedAmount() {
            // A reviewer needs to see the 12% as well as the money it produces.
            StructureTotals totals = SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "50000"),
                    percentDeduction("PF", "12")));

            assertThat(totals.deductions()).singleElement().satisfies(line -> {
                assertThat(line.configuredValue()).isEqualByComparingTo("12.00");
                assertThat(line.amount()).isEqualByComparingTo("6000.00");
                assertThat(line.calculationType()).isEqualTo(CalculationType.PERCENT_OF_BASIC);
            });
        }

        @Test
        void followAProratedBasicWithoutKnowingAboutAttendance() {
            // The payroll path (FR-5.4): hand the calculator an already-prorated basic and
            // the percentage deduction shrinks with it.
            ComponentAmount pf = percentDeduction("PF", "12");

            assertThat(SalaryStructureCalculator.amountOf(pf, new BigDecimal("41666.67")))
                    .isEqualByComparingTo("5000.00");
        }
    }

    @Nested
    @DisplayName("rounding")
    class Rounding {

        @Test
        void happensPerComponentSoLinesAddUpToTheTotal() {
            // NFR-3.3. Three thirds of a rupee: rounding once at the end would give 1.00
            // while the printed lines read 0.33, 0.33, 0.33.
            StructureTotals totals = SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "0.333"),
                    earning("A", "0.333"),
                    earning("B", "0.333")));

            assertThat(totals.earnings()).extracting(ComputedComponent::amount)
                    .allMatch(amount -> amount.compareTo(new BigDecimal("0.33")) == 0);
            assertThat(totals.grossMonthly()).isEqualByComparingTo("0.99");
        }

        @Test
        void roundsHalfUp() {
            StructureTotals totals = SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "1000"),
                    percentEarning("ODD", "0.125")));   // 1.25 exactly → 1.25, no rounding
            assertThat(totals.grossMonthly()).isEqualByComparingTo("1001.25");

            StructureTotals halfPaise = SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "1000"),
                    earning("ODD", "0.005")));          // half a paise rounds up
            assertThat(halfPaise.grossMonthly()).isEqualByComparingTo("1000.01");
        }

        @Test
        void everyFigureCarriesTwoDecimalPlaces() {
            StructureTotals totals = SalaryStructureCalculator.compute(List.of(earning("BASIC", "50000")));

            assertThat(totals.basic().scale()).isEqualTo(2);
            assertThat(totals.grossMonthly().scale()).isEqualTo(2);
            assertThat(totals.netMonthly().scale()).isEqualTo(2);
            assertThat(totals.annualCtc().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void requiresABasicComponent() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(earning("HRA", "20000"))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .anySatisfy(fieldError -> assertThat(fieldError.message()).contains("BASIC")));
        }

        @Test
        void requiresBasicToBePositive() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(earning("BASIC", "0"))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("not valid");
        }

        @Test
        void refusesBasicAsAPercentageOfItself() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(
                    List.of(percentEarning("BASIC", "50"))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesBasicAsADeduction() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(
                    List.of(deduction("BASIC", "50000"))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesADuplicatedComponent() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "50000"),
                    earning("HRA", "20000"),
                    earning("HRA", "10000"))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .anySatisfy(fieldError ->
                                    assertThat(fieldError.message()).contains("more than once")));
        }

        @Test
        void refusesANegativeValue() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "50000"),
                    deduction("PF", "-1"))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesAPercentageAboveOneHundred() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "50000"),
                    percentDeduction("PF", "120"))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesDeductionsThatExceedGross() {
            // A negative net salary is not a payslip anyone can act on.
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "10000"),
                    deduction("LOAN", "15000"))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(fieldError -> {
                                assertThat(fieldError.field()).isEqualTo("components");
                                assertThat(fieldError.message())
                                        .contains("exceed gross pay", "15000.00", "10000.00");
                            }));
        }

        @Test
        void allowsDeductionsThatExactlyConsumeGross() {
            assertThatCode(() -> SalaryStructureCalculator.compute(List.of(
                    earning("BASIC", "10000"),
                    deduction("LOAN", "10000"))))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesAnEmptyPackage() {
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of()))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(null))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void reportsEveryProblemAtOnceRatherThanOneAtATime() {
            // A form should be able to show all its errors in one round trip.
            assertThatThrownBy(() -> SalaryStructureCalculator.compute(List.of(
                    percentEarning("BASIC", "150"),
                    deduction("PF", "-5"))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .hasSizeGreaterThan(1));
        }
    }
}
