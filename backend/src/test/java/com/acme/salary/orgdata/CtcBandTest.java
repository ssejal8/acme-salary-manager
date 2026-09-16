package com.acme.salary.orgdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The CTC band decides whether a compensation package needs an override reason (FR-4.3),
 * so its boundaries are worth pinning down to the paise: one paise either side is the
 * difference between a package that goes through and one that needs sign-off.
 */
class CtcBandTest {

    private static final CtcBand G2 = new CtcBand(new BigDecimal("800000"), new BigDecimal("1500000"));

    @ParameterizedTest(name = "{0} inside 800000–1500000: {1}")
    @CsvSource({
            "799999.99, false",
            "800000.00, true",
            "800000.01, true",
            "1200000.00, true",
            "1499999.99, true",
            "1500000.00, true",
            "1500000.01, false",
    })
    void bothBoundsAreInclusive(String amount, boolean inside) {
        assertThat(G2.contains(new BigDecimal(amount))).isEqualTo(inside);
    }

    @Test
    void comparesByValueNotByScale() {
        // BigDecimal.equals would call 800000 and 800000.00 different numbers.
        assertThat(G2.contains(new BigDecimal("800000"))).isTrue();
        assertThat(G2.contains(new BigDecimal("800000.0000"))).isTrue();
    }

    @Test
    void anAbsentFloorAcceptsAnythingBelowTheCeiling() {
        CtcBand openBottom = new CtcBand(null, new BigDecimal("1500000"));

        assertThat(openBottom.contains(BigDecimal.ZERO)).isTrue();
        assertThat(openBottom.contains(new BigDecimal("1500001"))).isFalse();
    }

    @Test
    void anAbsentCeilingAcceptsAnythingAboveTheFloor() {
        CtcBand openTop = new CtcBand(new BigDecimal("4000000"), null);

        assertThat(openTop.contains(new BigDecimal("99000000"))).isTrue();
        assertThat(openTop.contains(new BigDecimal("3999999.99"))).isFalse();
    }

    @Test
    void anUnconfiguredBandNeverRejects() {
        // A grade with no band must not force an override reason on every assignment.
        assertThat(CtcBand.UNBOUNDED.isConfigured()).isFalse();
        assertThat(CtcBand.UNBOUNDED.contains(BigDecimal.ZERO)).isTrue();
        assertThat(CtcBand.UNBOUNDED.contains(new BigDecimal("999999999"))).isTrue();
    }

    @Test
    void reportsWhetherItIsConfiguredAtAll() {
        assertThat(G2.isConfigured()).isTrue();
        assertThat(new CtcBand(new BigDecimal("1"), null).isConfigured()).isTrue();
        assertThat(new CtcBand(null, new BigDecimal("1")).isConfigured()).isTrue();
        assertThat(new CtcBand(null, null).isConfigured()).isFalse();
    }

    @Test
    void anAbsentAmountIsNeverInsideABand() {
        assertThat(G2.contains(null)).isFalse();
        assertThat(CtcBand.UNBOUNDED.contains(null)).isFalse();
    }

    @Test
    void describesItselfForAnErrorMessage() {
        // This string reaches the user in the 400 that asks for an override reason.
        assertThat(G2.describe()).isEqualTo("800000–1500000");
        assertThat(new CtcBand(new BigDecimal("400000"), null).describe()).isEqualTo("400000–unbounded");
        assertThat(new CtcBand(null, new BigDecimal("800000")).describe()).isEqualTo("unbounded–800000");
        assertThat(CtcBand.UNBOUNDED.describe()).isEqualTo("unbounded");
    }

    @Test
    void isTheSameRuleTheGradeEntityApplies() {
        // Grade delegates to this record, so there is one implementation of the band test.
        Grade grade = new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000"));

        assertThat(grade.band()).isEqualTo(new CtcBand(
                new BigDecimal("800000.00"), new BigDecimal("1500000.00")));
        assertThat(grade.contains(new BigDecimal("840000"))).isTrue();
        assertThat(grade.contains(new BigDecimal("1500000.01"))).isFalse();
    }
}
