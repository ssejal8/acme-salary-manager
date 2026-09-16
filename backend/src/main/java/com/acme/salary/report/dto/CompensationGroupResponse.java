package com.acme.salary.report.dto;

import com.acme.salary.report.CompensationMetrics;
import java.math.BigDecimal;

/**
 * One row of a grouped compensation report — a department or a grade.
 *
 * @param groupId the department or grade id, so a client can drill through to the
 *     employee list filtered by it
 * @param shareOfMonthlyGross this group's percentage of the organisation's monthly gross,
 *     which is what makes the rows comparable at a glance
 */
public record CompensationGroupResponse(
        Long groupId,
        String groupName,
        CompensationMetricsResponse metrics,
        BigDecimal shareOfMonthlyGross) {

    public static CompensationGroupResponse from(
            Long groupId, String groupName, CompensationMetrics metrics, BigDecimal organisationGross) {
        return new CompensationGroupResponse(
                groupId,
                groupName,
                CompensationMetricsResponse.from(metrics),
                metrics.shareOf(organisationGross));
    }
}
