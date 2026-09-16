package com.acme.salary.report;

import com.acme.salary.salarystructure.StructureTotals;
import java.math.BigDecimal;

/**
 * One employee's current cost to the organisation, flattened for aggregation.
 *
 * <p>Only employees who actually hold a package appear as one of these. Those without are
 * counted separately, because a missing package is a gap to chase rather than a zero to
 * average in — averaging it in would quietly understate what everyone else is paid.
 */
public record EmployeeCompensation(
        Long employeeId,
        Long departmentId,
        String departmentName,
        Long gradeId,
        String gradeName,
        BigDecimal monthlyGross,
        BigDecimal monthlyDeductions,
        BigDecimal monthlyNet,
        BigDecimal annualCtc) {

    public static EmployeeCompensation of(
            Long employeeId,
            Long departmentId,
            String departmentName,
            Long gradeId,
            String gradeName,
            StructureTotals totals) {
        return new EmployeeCompensation(
                employeeId,
                departmentId,
                departmentName,
                gradeId,
                gradeName,
                totals.grossMonthly(),
                totals.totalDeductions(),
                totals.netMonthly(),
                totals.annualCtc());
    }
}
