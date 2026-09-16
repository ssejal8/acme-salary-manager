package com.acme.salary.report.dto;

import com.acme.salary.report.CompensationMetrics;
import java.math.BigDecimal;

/**
 * Summary statistics as the API exposes them. Amounts are strings at two decimal places,
 * like all money in this API (ADR-006).
 */
public record CompensationMetricsResponse(
        int headcount,
        int employeesWithPackage,
        int employeesWithoutPackage,
        BigDecimal totalMonthlyGross,
        BigDecimal totalMonthlyDeductions,
        BigDecimal totalMonthlyNet,
        BigDecimal totalAnnualCtc,
        BigDecimal averageMonthlyGross,
        BigDecimal medianMonthlyGross,
        BigDecimal lowestMonthlyGross,
        BigDecimal highestMonthlyGross) {

    public static CompensationMetricsResponse from(CompensationMetrics metrics) {
        return new CompensationMetricsResponse(
                metrics.headcount(),
                metrics.employeesWithPackage(),
                metrics.employeesWithoutPackage(),
                metrics.totalMonthlyGross(),
                metrics.totalMonthlyDeductions(),
                metrics.totalMonthlyNet(),
                metrics.totalAnnualCtc(),
                metrics.averageMonthlyGross(),
                metrics.medianMonthlyGross(),
                metrics.lowestMonthlyGross(),
                metrics.highestMonthlyGross());
    }
}
