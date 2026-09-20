package com.acme.salary.payroll.dto;

import com.acme.salary.payroll.PayrollRun;
import com.acme.salary.payroll.PayrollRunStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payroll run as it appears in a list: the summary FR-5.10 asks for, without its
 * payslips.
 *
 * <p>Deliberately excludes the payslips. A list of twelve months would otherwise carry
 * twelve thousand payslips and their lines, which is both slow and more salary data than a
 * list screen has any business holding (NFR-2.7).
 */
public record PayrollRunSummaryResponse(
        Long id,
        int periodYear,
        int periodMonth,
        String period,
        PayrollRunStatus status,
        int employeeCount,
        BigDecimal totalGross,
        BigDecimal totalDeductions,
        BigDecimal totalNet,
        Instant createdAt,
        Instant finalisedAt,
        Instant cancelledAt) {

    public static PayrollRunSummaryResponse from(PayrollRun run) {
        return new PayrollRunSummaryResponse(
                run.getId(),
                run.getPeriodYear(),
                run.getPeriodMonth(),
                run.period().describe(),
                run.getStatus(),
                run.getEmployeeCount(),
                run.getTotalGross(),
                run.getTotalDeductions(),
                run.getTotalNet(),
                run.getCreatedAt(),
                run.getFinalisedAt(),
                run.getCancelledAt());
    }
}
