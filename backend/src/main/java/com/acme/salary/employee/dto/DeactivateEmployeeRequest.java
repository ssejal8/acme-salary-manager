package com.acme.salary.employee.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request to record an employee's exit (FR-2.5).
 *
 * <p>The exit date is required rather than defaulted to today, and that is a payroll
 * decision rather than a form preference: eligibility for a period is derived from it
 * (FR-2.6), so a defaulted date would quietly decide whether someone is paid for the
 * month they left. It may be in the future — notice served, last day known — which is
 * exactly the case a "must be in the past" rule would break.
 *
 * <p>There is no status field. Deactivation sets it, and the only way to become INACTIVE
 * is to go through this endpoint, so status and exit date can never disagree — the
 * invariant the schema's {@code ck_employees_inactive_has_exit_date} also holds.
 */
public record DeactivateEmployeeRequest(
        @NotNull LocalDate exitDate) {
}
