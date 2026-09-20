package com.acme.salary.payroll.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Loss-of-pay days for one employee on a draft run (FR-5.6).
 *
 * <p>The upper bound is not expressed here because it depends on the period — February has
 * 28 days and March 31 — so the service checks it against the run's own month and reports
 * which month it was.
 */
public record LopAdjustment(
        @NotNull Long employeeId,
        @PositiveOrZero int lopDays) {
}
