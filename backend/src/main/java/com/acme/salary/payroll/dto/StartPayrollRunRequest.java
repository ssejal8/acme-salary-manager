package com.acme.salary.payroll.dto;

import com.acme.salary.payroll.PayrollPeriod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request to start a payroll run for a period (FR-5.1).
 *
 * <p>The bounds mirror the database's {@code ck_payroll_runs_year} and
 * {@code ck_payroll_runs_month} checks, so an impossible month is a 400 naming the field
 * rather than a constraint violation surfacing as a 409.
 */
public record StartPayrollRunRequest(
        @NotNull @Min(PayrollPeriod.MIN_YEAR) @Max(PayrollPeriod.MAX_YEAR) Integer periodYear,
        @NotNull @Min(1) @Max(12) Integer periodMonth) {

    public PayrollPeriod toPeriod() {
        return PayrollPeriod.of(periodYear, periodMonth);
    }
}
