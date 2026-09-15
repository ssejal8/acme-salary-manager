package com.acme.salary.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Money rules carry the strictest test bar in the codebase (NFR-5.3): everything payroll
 * computes flows through this class.
 */
class MoneyTest {

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        void roundsHalfUpToTwoDecimals() {
            assertThat(Money.normalize(new BigDecimal("100.005"))).isEqualByComparingTo("100.01");
            assertThat(Money.normalize(new BigDecimal("100.004"))).isEqualByComparingTo("100.00");
        }

        @Test
        void padsScaleSoAmountsSerialiseConsistently() {
            assertThat(Money.normalize(new BigDecimal("100")).toPlainString()).isEqualTo("100.00");
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> Money.normalize(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("prorate")
    class Prorate {

        @Test
        void returnsFullAmountWhenNoDaysAreLost() {
            assertThat(Money.prorate(Money.of("50000"), 30, 30)).isEqualByComparingTo("50000.00");
        }

        @Test
        void proratesByPaidDaysOverTotalDays() {
            // 5 LOP days in a 30-day month: the acceptance scenario in requirements §7.
            assertThat(Money.prorate(Money.of("50000"), 25, 30)).isEqualByComparingTo("41666.67");
        }

        @Test
        void returnsZeroWhenNoDaysArePaid() {
            assertThat(Money.prorate(Money.of("50000"), 0, 31)).isEqualByComparingTo("0.00");
        }

        @Test
        void handlesFebruaryAndThirtyOneDayMonthsIdentically() {
            assertThat(Money.prorate(Money.of("28000"), 14, 28)).isEqualByComparingTo("14000.00");
            assertThat(Money.prorate(Money.of("31000"), 15, 31)).isEqualByComparingTo("15000.00");
        }

        @Test
        void rejectsImpossibleDayCounts() {
            assertThatThrownBy(() -> Money.prorate(Money.of("100"), 5, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.prorate(Money.of("100"), -1, 30))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.prorate(Money.of("100"), 31, 30))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("percentOf")
    class PercentOf {

        @Test
        void computesStatutoryStyleDeduction() {
            assertThat(Money.percentOf(Money.of("50000"), new BigDecimal("12")))
                    .isEqualByComparingTo("6000.00");
        }

        @Test
        void roundsTheResultRatherThanTheBase() {
            // 12% of a prorated basic: the FR-5.4 case, where the base is itself rounded.
            assertThat(Money.percentOf(Money.of("41666.67"), new BigDecimal("12")))
                    .isEqualByComparingTo("5000.00");
        }
    }

    @Nested
    @DisplayName("sum")
    class Sum {

        @Test
        void addsRoundedLinesSoTotalsMatchWhatIsPrinted() {
            // NFR-3.3: the printed lines must add up to the printed total.
            List<BigDecimal> lines = List.of(Money.of("41666.67"), Money.of("16666.67"), Money.of("8333.33"));
            assertThat(Money.sum(lines)).isEqualByComparingTo("66666.67");
        }

        @Test
        void sumsToZeroWhenEmpty() {
            assertThat(Money.sum(List.of())).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("inWords")
    class InWords {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                0.00        | Rupees Zero Only
                1.00        | Rupees One Only
                19.00       | Rupees Nineteen Only
                20.00       | Rupees Twenty Only
                75.00       | Rupees Seventy Five Only
                100.00      | Rupees One Hundred Only
                999.00      | Rupees Nine Hundred Ninety Nine Only
                1000.00     | Rupees One Thousand Only
                64000.00    | Rupees Sixty Four Thousand Only
                100000.00   | Rupees One Lakh Only
                1250000.00  | Rupees Twelve Lakh Fifty Thousand Only
                10000000.00 | Rupees One Crore Only
                """)
        void rendersRupeesInIndianConvention(String amount, String expected) {
            assertThat(Money.inWords(new BigDecimal(amount))).isEqualTo(expected);
        }

        @Test
        void appendsPaiseWhenPresent() {
            assertThat(Money.inWords(new BigDecimal("64000.50")))
                    .isEqualTo("Rupees Sixty Four Thousand and Fifty Paise Only");
            assertThat(Money.inWords(new BigDecimal("0.05")))
                    .isEqualTo("Rupees Zero and Five Paise Only");
        }

        @Test
        void rejectsNegativeAmountsBecauseNoPayslipCarriesOne() {
            assertThatThrownBy(() -> Money.inWords(new BigDecimal("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("comparison helpers")
    class Comparison {

        @Test
        void equalityIgnoresScaleUnlikeBigDecimalEquals() {
            assertThat(new BigDecimal("100.0")).isNotEqualTo(new BigDecimal("100.00"));
            assertThat(Money.eq(new BigDecimal("100.0"), new BigDecimal("100.00"))).isTrue();
        }

        @Test
        void detectsSign() {
            assertThat(Money.isZero(Money.ZERO)).isTrue();
            assertThat(Money.isNegative(new BigDecimal("-0.01"))).isTrue();
            assertThat(Money.isNegative(Money.of("0.01"))).isFalse();
        }
    }
}
