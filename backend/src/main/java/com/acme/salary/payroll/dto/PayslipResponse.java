package com.acme.salary.payroll.dto;

import com.acme.salary.common.money.Money;
import com.acme.salary.payroll.Payslip;
import com.acme.salary.payroll.PayslipLine;
import java.math.BigDecimal;
import java.util.List;

/**
 * One employee's payslip (FR-6.2).
 *
 * <p>Earnings and deductions are separated rather than sent as one list with a type flag,
 * because every consumer — the review screen, the payslip view, the PDF — renders them as
 * two blocks with their own subtotals.
 *
 * @param netPayInWords FR-6.3. Rendered server-side because it is derived from the amount
 *     and must match it exactly; a client that spelled it out itself could disagree with
 *     the figure beside it.
 */
public record PayslipResponse(
        Long id,
        Long employeeId,
        int totalDays,
        int paidDays,
        int lopDays,
        BigDecimal grossPay,
        BigDecimal totalDeductions,
        BigDecimal netPay,
        String netPayInWords,
        List<PayslipLineResponse> earnings,
        List<PayslipLineResponse> deductions) {

    public static PayslipResponse from(Payslip payslip) {
        return new PayslipResponse(
                payslip.getId(),
                payslip.getEmployeeId(),
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
