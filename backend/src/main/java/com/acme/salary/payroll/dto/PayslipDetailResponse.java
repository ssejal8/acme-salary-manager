package com.acme.salary.payroll.dto;

import com.acme.salary.common.money.Money;
import com.acme.salary.employee.EmployeeIdentity;
import com.acme.salary.payroll.Payslip;
import com.acme.salary.payroll.PayslipLine;
import com.acme.salary.payroll.PayrollRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A payslip as an employee or HR reads it (FR-6.2).
 *
 * <p>Richer than the {@link PayslipResponse} nested in a run: that one sits inside a review
 * screen that already knows the period and has the employee list beside it, while this one
 * has to stand alone — so it names the employee and the period it is for.
 *
 * <p>Every figure comes from the payslip's own columns, never from the employee's current
 * package. A payslip is the record of what was paid; reading the figures from the package
 * in force would make a two-year-old payslip change when somebody gets a raise.
 *
 * @param published whether the run has been finalised. A draft payslip is visible to HR
 *     during review but is not published, and an employee must never be shown one
 *     (FR-5.8) — this says which it is rather than leaving the reader to infer it.
 * @param netPayInWords FR-6.3, rendered server-side so it cannot disagree with the figure
 *     beside it.
 */
public record PayslipDetailResponse(
        Long id,
        int periodYear,
        int periodMonth,
        String period,
        PayrollRunStatus runStatus,
        boolean published,
        Instant publishedAt,
        Employee employee,
        int totalDays,
        int paidDays,
        int lopDays,
        BigDecimal grossPay,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String netPayInWords,
        List<PayslipLineResponse> earnings,
        List<PayslipLineResponse> deductions) {

    /**
     * Who the payslip is for.
     *
     * <p>Nested rather than flattened so the identity fields cannot be confused with the
     * payslip's own, and so a PDF renderer has one object to hand to its header.
     */
    public record Employee(
            Long id,
            String employeeCode,
            String fullName,
            String workEmail,
            String department,
            String designation) {

        static Employee from(EmployeeIdentity identity) {
            return new Employee(
                    identity.employeeId(),
                    identity.employeeCode(),
                    identity.fullName(),
                    identity.workEmail(),
                    identity.departmentName(),
                    identity.designationTitle());
        }
    }

    public static PayslipDetailResponse from(Payslip payslip, EmployeeIdentity identity) {
        var run = payslip.getRun();
        return new PayslipDetailResponse(
                payslip.getId(),
                run.getPeriodYear(),
                run.getPeriodMonth(),
                run.period().describe(),
                run.getStatus(),
                run.isFinalised(),
                run.getFinalisedAt(),
                Employee.from(identity),
                payslip.getTotalDays(),
                payslip.getPaidDays(),
                payslip.getLopDays(),
                payslip.getGrossPay(),
                payslip.getTotalDeductions(),
                payslip.getNetPay(),
                Money.inWords(payslip.getNetPay()),
                map(payslip.getEarnings()),
                map(payslip.getDeductions()));
    }

    private static List<PayslipLineResponse> map(List<PayslipLine> lines) {
        return lines.stream().map(PayslipLineResponse::from).toList();
    }
}
