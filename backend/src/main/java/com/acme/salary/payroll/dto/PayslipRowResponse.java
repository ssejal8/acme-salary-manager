package com.acme.salary.payroll.dto;

import com.acme.salary.employee.EmployeeIdentity;
import com.acme.salary.payroll.Payslip;
import com.acme.salary.payroll.PayrollRunStatus;
import java.math.BigDecimal;

/**
 * One row of a payslip list — the payroll register, the HR-wide search, and the table a
 * draft run is reviewed in (FR-6.5, FR-7.1).
 *
 * <p>Carries the employee's name and department, which the payslip itself does not hold —
 * a payslip names its employee by id (ADR-001) — so the service resolves identities for
 * the whole page in one batch rather than a query per row.
 *
 * <p>Deliberately without the earning and deduction lines. A page of a hundred payslips
 * would otherwise carry six hundred line items to render a table that shows none of them,
 * and the payslip view already fetches one payslip in full when somebody opens it.
 */
public record PayslipRowResponse(
        Long id,
        Long runId,
        int periodYear,
        int periodMonth,
        /** The API's compact form, e.g. {@code 2026-08}. */
        String period,
        PayrollRunStatus runStatus,
        /** Whether the run is finalised, so a list can mark a draft row as unpublished. */
        boolean published,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String department,
        int totalDays,
        int paidDays,
        int lopDays,
        BigDecimal grossPay,
        BigDecimal totalDeductions,
        BigDecimal netPay) {

    /**
     * @param identity may be null for an employee whose record has since been removed from
     *     the reference data — impossible today, since nothing is ever deleted (ADR-014),
     *     but a row with a blank name is a better answer than a 500
     */
    public static PayslipRowResponse from(Payslip payslip, EmployeeIdentity identity) {
        return new PayslipRowResponse(
                payslip.getId(),
                payslip.getRun().getId(),
                payslip.getRun().getPeriodYear(),
                payslip.getRun().getPeriodMonth(),
                payslip.getRun().period().describe(),
                payslip.getRun().getStatus(),
                payslip.getRun().isFinalised(),
                payslip.getEmployeeId(),
                identity == null ? null : identity.employeeCode(),
                identity == null ? null : identity.fullName(),
                identity == null ? null : identity.departmentName(),
                payslip.getTotalDays(),
                payslip.getPaidDays(),
                payslip.getLopDays(),
                payslip.getGrossPay(),
                payslip.getTotalDeductions(),
                payslip.getNetPay());
    }
}
