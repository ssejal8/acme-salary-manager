package com.acme.salary.orgdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The grade band is the only compensation guard in the system (FR-4.3). */
class GradeTest {

    @Test
    void acceptsCtcInsideTheBandInclusiveOfBothBounds() {
        Grade grade = new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000"));

        assertThat(grade.contains(new BigDecimal("800000"))).isTrue();
        assertThat(grade.contains(new BigDecimal("1200000"))).isTrue();
        assertThat(grade.contains(new BigDecimal("1500000"))).isTrue();
    }

    @Test
    void rejectsCtcOutsideTheBand() {
        Grade grade = new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000"));

        assertThat(grade.contains(new BigDecimal("799999.99"))).isFalse();
        assertThat(grade.contains(new BigDecimal("1500000.01"))).isFalse();
    }

    @Test
    void anAbsentBoundNeverRejects() {
        Grade openTop = new Grade("G5", new BigDecimal("4000000"), null);
        assertThat(openTop.contains(new BigDecimal("99000000"))).isTrue();
        assertThat(openTop.contains(new BigDecimal("100000"))).isFalse();

        Grade unbounded = new Grade("G0", null, null);
        assertThat(unbounded.hasBand()).isFalse();
        assertThat(unbounded.contains(new BigDecimal("1"))).isTrue();
    }

    @Test
    void normalisesBoundsToMoneyScale() {
        Grade grade = new Grade("G1", new BigDecimal("400000.005"), new BigDecimal("800000"));

        assertThat(grade.getMinCtc()).isEqualByComparingTo("400000.01");
        assertThat(grade.getMaxCtc().toPlainString()).isEqualTo("800000.00");
    }

    @Test
    void rejectsAnInvertedBand() {
        assertThatThrownBy(() -> new Grade("G2", new BigDecimal("1500000"), new BigDecimal("800000")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNegativeBounds() {
        assertThatThrownBy(() -> new Grade("G2", new BigDecimal("-1"), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Grade(" ", null, null)).isInstanceOf(ValidationException.class);
    }
}
