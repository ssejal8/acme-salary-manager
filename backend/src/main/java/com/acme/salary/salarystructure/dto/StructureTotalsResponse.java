package com.acme.salary.salarystructure.dto;

import com.acme.salary.salarystructure.StructureTotals;
import java.math.BigDecimal;

/**
 * The computed bottom line of a compensation package (FR-4.5).
 *
 * <p>Every figure is a string at two decimal places, like all money in this API, and is
 * computed server-side: the browser's preview is indicative, these numbers are
 * authoritative (architecture §6.3).
 */
public record StructureTotalsResponse(
        BigDecimal basicMonthly,
        BigDecimal grossMonthly,
        BigDecimal totalDeductions,
        BigDecimal netMonthly,
        BigDecimal annualCtc) {

    public static StructureTotalsResponse from(StructureTotals totals) {
        return new StructureTotalsResponse(
                totals.basic(),
                totals.grossMonthly(),
                totals.totalDeductions(),
                totals.netMonthly(),
                totals.annualCtc());
    }
}
