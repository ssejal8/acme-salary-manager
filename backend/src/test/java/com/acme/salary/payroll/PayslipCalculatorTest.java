package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ApiError;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.money.Money;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarystructure.ComponentAmount;
import java.math.BigDecimal;
import java.util.List;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The payroll arithmetic. No Spring, no database.
 *
 * <p>This is the class the project's 90% coverage bar is aimed at, so the cases here are
 * chosen for the ways money goes wrong rather than for line coverage: the proration rule
 * per component kind, the invariant that lines add up to totals, and the edge where
 * proration turns a valid package into an unpayable one.
 *
 * <p>Figures follow employee {@code E-1001}'s seeded package — a 75,000 basic in a 150,000
 * gross, PF at 12% — so the expected numbers can be checked against the dev data.
 */
class PayslipCalculatorTest {

    private static final int APRIL_DAYS = 30;

    private static ComponentAmount basic(String value) {
        return component(1L, "BASIC", "Basic Salary", ComponentType.EARNING, CalculationType.FLAT, value);
    }

    private static ComponentAmount flatEarning(long id, String code, String value) {
        return component(id, code, code, ComponentType.EARNING, CalculationType.FLAT, value);
    }

    private static ComponentAmount flatDeduction(long id, String code, String value) {
        return component(id, code, code, ComponentType.DEDUCTION, CalculationType.FLAT, value);
    }

    private static ComponentAmount percentDeduction(long id, String code, String percent) {
        return component(
                id, code, code, ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC, percent);
    }

    private static ComponentAmount percentEarning(long id, String code, String percent) {
        return component(
                id, code, code, ComponentType.EARNING, CalculationType.PERCENT_OF_BASIC, percent);
    }

    private static ComponentAmount component(
            long id,
            String code,
            String name,
            ComponentType type,
            CalculationType calculationType,
            String value) {
        return new ComponentAmount(id, code, name, type, calculationType, Money.of(value));
    }

    /** E-1001's package: 150,000 gross, 9,200 deductions, 140,800 net at full attendance. */
    private static List<ComponentAmount> seededPackage() {
        return List.of(
                basic("75000.00"),
                flatEarning(2L, "HRA", "30000.00"),
                flatEarning(3L, "CONVEYANCE", "2000.00"),
                flatEarning(4L, "SPECIAL", "43000.00"),
                percentDeduction(5L, "PF", "12.00"),
                flatDeduction(6L, "PROF_TAX", "200.00"));
    }

    /**
     * The field-error detail of a rejection.
     *
     * <p>{@code ValidationException.field(...)} sets the message to a flat "Validation
     * failed" and carries the specifics as field errors, so asserting on {@code
     * getMessage()} would pass for any rejection at all.
     */
    private static String rejectionDetail(ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
        } catch (ValidationException e) {
            return e.fieldErrors().stream()
                    .map(ApiError.FieldError::message)
                    .collect(java.util.stream.Collectors.joining(" | "));
        } catch (Throwable other) {
            throw new AssertionError("expected a ValidationException, got " + other, other);
        }
        throw new AssertionError("expected the computation to be rejected");
    }

    private static BigDecimal amountOf(PayslipAmounts payslip, String code) {
        return payslip.lines().stream()
                .filter(line -> line.code().equals(code))
                .map(PayslipAmounts.Line::amount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + code));
    }

    @Nested
    class AtFullAttendance {

        @Test
        void reproducesTheStructureTotalsExactly() {
            // A month with no loss of pay must pay exactly what the package is worth.
            // Anything else would mean the payroll engine and the assignment preview
            // disagree about the same package.
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 0, APRIL_DAYS);

            assertThat(payslip.grossPay()).isEqualByComparingTo("150000.00");
            assertThat(payslip.totalDeductions()).isEqualByComparingTo("9200.00");
            assertThat(payslip.netPay()).isEqualByComparingTo("140800.00");
            assertThat(payslip.proratedBasic()).isEqualByComparingTo("75000.00");
        }

        @Test
        void reportsTheDayCounts() {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 0, APRIL_DAYS);

            assertThat(payslip.totalDays()).isEqualTo(30);
            assertThat(payslip.paidDays()).isEqualTo(30);
            assertThat(payslip.lopDays()).isZero();
        }

        @Test
        void takesAPercentageAgainstTheFullBasic() {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 0, APRIL_DAYS);

            // 12% of 75,000.
            assertThat(amountOf(payslip, "PF")).isEqualByComparingTo("9000.00");
        }
    }

    @Nested
    class Proration {

        @Test
        void proratesAFlatEarningByPaidDays() {
            // 15 of 30 days: half the basic.
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 15, APRIL_DAYS);

            assertThat(amountOf(payslip, "BASIC")).isEqualByComparingTo("37500.00");
            assertThat(amountOf(payslip, "HRA")).isEqualByComparingTo("15000.00");
            assertThat(payslip.proratedBasic()).isEqualByComparingTo("37500.00");
        }

        @Test
        void takesAPercentageDeductionAgainstTheProratedBasic() {
            // FR-5.4 in one assertion: 12% of the *prorated* 37,500, not of 75,000.
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 15, APRIL_DAYS);

            assertThat(amountOf(payslip, "PF")).isEqualByComparingTo("4500.00");
        }

        @Test
        void doesNotProrateAPercentageComponentTwice() {
            // The trap this guards: prorating the basic and then prorating the percentage
            // of it as well would give 2,250 rather than 4,500 — a quarter instead of a
            // half.
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 15, APRIL_DAYS);

            assertThat(amountOf(payslip, "PF")).isEqualByComparingTo("4500.00");
            assertThat(amountOf(payslip, "PF"))
                    .isEqualByComparingTo(Money.percentOf(payslip.proratedBasic(), Money.of("12")));
        }

        @Test
        void leavesAFlatDeductionAlone() {
            // Professional tax is a fixed statutory charge; it does not shrink because
            // somebody took unpaid leave. FR-5.4 mandates proration for earnings only,
            // and this is the reading of that silence.
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 15, APRIL_DAYS);

            assertThat(amountOf(payslip, "PROF_TAX")).isEqualByComparingTo("200.00");
        }

        @Test
        void proratesAPercentageEarningThroughTheBasicRatherThanTwice() {
            List<ComponentAmount> components = List.of(
                    basic("50000.00"), percentEarning(9L, "BONUS_PCT", "10.00"));

            PayslipAmounts payslip = PayslipCalculator.compute(components, 15, APRIL_DAYS);

            // 10% of the prorated 25,000 basic, not 10% of 50,000 prorated again.
            assertThat(amountOf(payslip, "BONUS_PCT")).isEqualByComparingTo("2500.00");
        }

        @ParameterizedTest
        @CsvSource({
            // lopDays, expected basic — a 75,000 basic over a 30-day month.
            "0, 75000.00",
            "1, 72500.00",
            "10, 50000.00",
            "15, 37500.00",
            "29, 2500.00",
            // 30 is absent on purpose: with a flat PROF_TAX against zero gross the
            // package is unpayable, which WhatItRefuses covers directly.
        })
        void scalesTheBasicWithPaidDays(int lopDays, String expectedBasic) {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), lopDays, APRIL_DAYS);

            assertThat(payslip.proratedBasic()).isEqualByComparingTo(expectedBasic);
        }

        @Test
        void dividesByTheRealMonthLength() {
            // February is not 30 days. A hardcoded denominator would overpay or underpay
            // every February and every 31-day month.
            PayslipAmounts february = PayslipCalculator.compute(
                    List.of(basic("28000.00")), 14, 28);
            PayslipAmounts march = PayslipCalculator.compute(
                    List.of(basic("31000.00")), 1, 31);

            assertThat(february.proratedBasic()).isEqualByComparingTo("14000.00");
            assertThat(march.proratedBasic()).isEqualByComparingTo("30000.00");
        }

        @Test
        void roundsHalfUpPerComponent() {
            // 1 of 3 days on 100.00 is 33.333…; half-up at two places is 33.33.
            PayslipAmounts payslip = PayslipCalculator.compute(List.of(basic("100.00")), 2, 3);

            assertThat(payslip.proratedBasic()).isEqualByComparingTo("33.33");
        }
    }

    /**
     * The invariant that makes a payslip checkable by hand (NFR-3.3): each line is rounded
     * before anything is summed, so the printed lines add up to the printed total exactly.
     */
    @Nested
    class TotalsTieToLines {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 7, 13, 15, 22, 29})
        void theLinesAlwaysAddUpToTheTotals(int lopDays) {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), lopDays, APRIL_DAYS);

            BigDecimal summedEarnings = Money.sum(
                    payslip.earnings().stream().map(PayslipAmounts.Line::amount).toList());
            BigDecimal summedDeductions = Money.sum(
                    payslip.deductions().stream().map(PayslipAmounts.Line::amount).toList());

            assertThat(summedEarnings).isEqualByComparingTo(payslip.grossPay());
            assertThat(summedDeductions).isEqualByComparingTo(payslip.totalDeductions());
            assertThat(payslip.netPay())
                    .isEqualByComparingTo(payslip.grossPay().subtract(payslip.totalDeductions()));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 9, 17, 29})
        void paidAndLopDaysAlwaysAccountForTheWholePeriod(int lopDays) {
            // The database asserts the same thing in ck_payslips_days; failing it here
            // means a run would be rejected by a constraint rather than by a message.
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), lopDays, APRIL_DAYS);

            assertThat(payslip.paidDays() + payslip.lopDays()).isEqualTo(payslip.totalDays());
        }

        @Test
        void everyAmountIsAtTwoDecimalPlaces() {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 13, APRIL_DAYS);

            for (PayslipAmounts.Line line : payslip.lines()) {
                assertThat(line.amount().scale()).as(line.code()).isEqualTo(Money.SCALE);
            }
            assertThat(payslip.grossPay().scale()).isEqualTo(Money.SCALE);
            assertThat(payslip.netPay().scale()).isEqualTo(Money.SCALE);
        }
    }

    @Nested
    class WhatItRefuses {

        @Test
        void aFullMonthOfLossOfPayPaysNothingRatherThanFailing() {
            // Zero pay is a legitimate outcome — unpaid leave for a whole month — so long
            // as the flat deductions do not push net below zero.
            PayslipAmounts payslip = PayslipCalculator.compute(
                    List.of(basic("75000.00"), percentDeduction(5L, "PF", "12.00")), 30, APRIL_DAYS);

            assertThat(payslip.grossPay()).isEqualByComparingTo("0.00");
            assertThat(payslip.totalDeductions()).isEqualByComparingTo("0.00");
            assertThat(payslip.netPay()).isEqualByComparingTo("0.00");
        }

        @Test
        void refusesAPackageWhereProrationLeavesNetNegative() {
            // The interesting failure: valid at full attendance, unpayable at 29 days of
            // loss of pay, because the flat deduction does not shrink with the earnings.
            List<ComponentAmount> components =
                    List.of(basic("30000.00"), flatDeduction(6L, "PROF_TAX", "5000.00"));

            assertThat(PayslipCalculator.compute(components, 0, APRIL_DAYS).netPay())
                    .isEqualByComparingTo("25000.00");

            assertThat(rejectionDetail(() -> PayslipCalculator.compute(components, 30, APRIL_DAYS)))
                    .contains("exceed prorated gross pay")
                    .contains("0 of 30 paid days");
        }

        @Test
        void refusesAStructureWithNoBasic() {
            assertThat(rejectionDetail(() -> PayslipCalculator.compute(
                    List.of(flatEarning(2L, "HRA", "10000.00")), 0, APRIL_DAYS)))
                    .contains("no BASIC component");
        }

        @Test
        void refusesAnEmptyOrAbsentStructure() {
            assertThatThrownBy(() -> PayslipCalculator.compute(List.of(), 0, APRIL_DAYS))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> PayslipCalculator.compute(null, 0, APRIL_DAYS))
                    .isInstanceOf(ValidationException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 31, 100})
        void refusesLossOfPayDaysOutsideThePeriod(int lopDays) {
            assertThat(rejectionDetail(
                    () -> PayslipCalculator.compute(seededPackage(), lopDays, APRIL_DAYS)))
                    .contains("between 0 and 30");
        }

        @Test
        void refusesANonsensicalPeriodLength() {
            assertThat(rejectionDetail(() -> PayslipCalculator.compute(seededPackage(), 0, 0)))
                    .contains("must be positive");
        }
    }

    @Nested
    class Lines {

        @Test
        void keepsTheStructuresOrderSoAPayslipReadsTheSameEveryMonth() {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 0, APRIL_DAYS);

            assertThat(payslip.lines()).extracting(PayslipAmounts.Line::code)
                    .containsExactly("BASIC", "HRA", "CONVEYANCE", "SPECIAL", "PF", "PROF_TAX");
            assertThat(payslip.lines()).extracting(PayslipAmounts.Line::sortOrder)
                    .containsExactly(0, 1, 2, 3, 4, 5);
        }

        @Test
        void separatesEarningsFromDeductions() {
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 0, APRIL_DAYS);

            assertThat(payslip.earnings()).extracting(PayslipAmounts.Line::code)
                    .containsExactly("BASIC", "HRA", "CONVEYANCE", "SPECIAL");
            assertThat(payslip.deductions()).extracting(PayslipAmounts.Line::code)
                    .containsExactly("PF", "PROF_TAX");
        }

        @Test
        void carriesTheComponentNameSoThePayslipCanStandAlone() {
            // payslip_lines copies the name rather than joining, so a later rename cannot
            // retitle a published line (ADR-010).
            PayslipAmounts payslip = PayslipCalculator.compute(seededPackage(), 0, APRIL_DAYS);

            assertThat(payslip.lines().get(0).name()).isEqualTo("Basic Salary");
        }
    }
}
