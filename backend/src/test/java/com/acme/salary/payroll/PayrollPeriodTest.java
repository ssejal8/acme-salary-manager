package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PayrollPeriodTest {

    @Test
    void reportsItsBoundaries() {
        PayrollPeriod april = PayrollPeriod.of(2026, 4);

        assertThat(april.firstDay()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(april.lastDay()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(april.describe()).isEqualTo("2026-04");
    }

    @ParameterizedTest
    @CsvSource({
        "2026, 1, 31",
        "2026, 2, 28",
        "2026, 4, 30",
        "2026, 12, 31",
        // 2028 is a leap year, so February is the length the calendar says and not a
        // hardcoded 28 or 30. This is the proration denominator (FR-5.4).
        "2028, 2, 29",
        "2100, 2, 28",
    })
    void takesItsLengthFromTheCalendar(int year, int month, int expectedDays) {
        assertThat(PayrollPeriod.of(year, month).totalDays()).isEqualTo(expectedDays);
    }

    @Test
    void neverReportsALengthTheSchemaWouldReject() {
        // ck_payslips_days permits 28 to 31, so any month must fall inside that.
        for (int month = 1; month <= 12; month++) {
            assertThat(PayrollPeriod.of(2028, month).totalDays()).isBetween(28, 31);
        }
    }

    @Test
    void knowsWhetherItHasFinished() {
        PayrollPeriod april = PayrollPeriod.of(2026, 4);

        assertThat(april.hasEndedBy(LocalDate.of(2026, 4, 30))).isTrue();
        assertThat(april.hasEndedBy(LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(april.hasEndedBy(LocalDate.of(2026, 4, 29))).isFalse();
        assertThat(april.hasEndedBy(LocalDate.of(2026, 1, 1))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13, -1, 100})
    void refusesAMonthOutsideTheCalendar(int month) {
        // Mirrors ck_payroll_runs_month, so this is a 400 naming the field rather than a
        // constraint violation surfacing as a 409.
        assertThatThrownBy(() -> PayrollPeriod.of(2026, month))
                .isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1999, 2101, 0})
    void refusesAYearOutsideTheSchemasRange(int year) {
        assertThatThrownBy(() -> PayrollPeriod.of(year, 4))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptsTheBoundaryYears() {
        assertThat(PayrollPeriod.of(PayrollPeriod.MIN_YEAR, 1).describe()).isEqualTo("2000-01");
        assertThat(PayrollPeriod.of(PayrollPeriod.MAX_YEAR, 12).describe()).isEqualTo("2100-12");
    }

    @Test
    void comparesByValue() {
        // A record, so two periods for the same month are the same key — which is what
        // lets the service use one as a map key or compare without an equals of its own.
        assertThat(PayrollPeriod.of(2026, 4)).isEqualTo(PayrollPeriod.of(2026, 4));
        assertThat(PayrollPeriod.of(2026, 4)).isNotEqualTo(PayrollPeriod.of(2026, 5));
    }
}
