package com.acme.salary.payroll;

import com.acme.salary.common.error.ValidationException;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The calendar month a payroll run belongs to (FR-5.1).
 *
 * <p>A value object rather than two loose ints, because almost every payroll rule is
 * expressed against the period's boundaries: eligibility is "joined on or before the end
 * and not gone before the start" (FR-2.6, FR-5.2), the governing salary revision is the
 * one effective on the last day (FR-5.3), and proration divides by the days in the month
 * (FR-5.4). Passing a year and a month around separately invites getting one of those
 * wrong.
 *
 * <p>The month length comes from {@link YearMonth}, so February is 28 or 29 days as the
 * year requires — which is also why the schema's {@code ck_payslips_days} check permits 28
 * to 31 rather than assuming 30.
 *
 * <p>Bounds mirror the database's {@code ck_payroll_runs_year} and
 * {@code ck_payroll_runs_month} checks. Validating here as well means a bad period is a
 * 400 naming the field, not a constraint violation surfacing as a 409.
 */
public record PayrollPeriod(int year, int month) {

    /** Matches {@code ck_payroll_runs_year}. */
    public static final int MIN_YEAR = 2000;
    public static final int MAX_YEAR = 2100;

    public PayrollPeriod {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw ValidationException.field(
                    "periodYear", "must be between %d and %d".formatted(MIN_YEAR, MAX_YEAR));
        }
        if (month < 1 || month > 12) {
            throw ValidationException.field("periodMonth", "must be between 1 and 12");
        }
    }

    public static PayrollPeriod of(int year, int month) {
        return new PayrollPeriod(year, month);
    }

    private YearMonth yearMonth() {
        return YearMonth.of(year, month);
    }

    public LocalDate firstDay() {
        return yearMonth().atDay(1);
    }

    public LocalDate lastDay() {
        return yearMonth().atEndOfMonth();
    }

    /** The proration denominator: calendar days in the month, 28 to 31 (FR-5.4). */
    public int totalDays() {
        return yearMonth().lengthOfMonth();
    }

    /** Whether this period has finished, which is what makes a run payable. */
    public boolean hasEndedBy(LocalDate date) {
        return !lastDay().isAfter(date);
    }

    /** For a message or an audit detail, e.g. {@code 2026-04}. */
    public String describe() {
        return "%04d-%02d".formatted(year, month);
    }
}
