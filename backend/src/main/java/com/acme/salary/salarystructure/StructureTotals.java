package com.acme.salary.salarystructure;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a compensation package comes to at a full month's attendance (FR-4.5).
 *
 * @param basic the basic component's amount, the base for percentage deductions
 * @param grossMonthly sum of rounded earnings
 * @param totalDeductions sum of rounded deductions
 * @param netMonthly gross less deductions
 * @param annualCtc gross annualised — cost to company, per requirements §1.3
 */
public record StructureTotals(
        BigDecimal basic,
        BigDecimal grossMonthly,
        BigDecimal totalDeductions,
        BigDecimal netMonthly,
        BigDecimal annualCtc,
        List<ComputedComponent> earnings,
        List<ComputedComponent> deductions) {
}
