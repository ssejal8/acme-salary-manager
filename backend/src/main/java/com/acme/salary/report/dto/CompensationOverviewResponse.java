package com.acme.salary.report.dto;

import java.time.Instant;
import java.util.List;

/**
 * The compensation dashboard: what the organisation currently costs, and how that cost
 * splits by department and grade (FR-7.4).
 *
 * <p>This reports the cost of the packages in force <em>now</em>. It is not a payroll
 * register: it knows nothing about attendance, loss of pay, or any particular month's
 * run, so it will differ from an actual payroll total whenever someone has unpaid days.
 * The period-based register arrives with payroll runs (FR-7.1).
 *
 * @param generatedAt when these figures were computed, from the application clock
 */
public record CompensationOverviewResponse(
        Instant generatedAt,
        CompensationMetricsResponse organisation,
        List<CompensationGroupResponse> byDepartment,
        List<CompensationGroupResponse> byGrade) {
}
