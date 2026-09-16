package com.acme.salary.report;

import com.acme.salary.report.dto.CompensationGroupResponse;
import com.acme.salary.report.dto.CompensationMetricsResponse;
import com.acme.salary.report.dto.CompensationOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compensation analytics over the packages currently in force.
 *
 * <p>ADMIN and HR only, and not negotiable: these endpoints aggregate the most sensitive
 * data in the system (NFR-2.7). An aggregate is not anonymous — a department of one
 * discloses that person's salary exactly — so there is no relaxed variant for other
 * roles.
 */
@RestController
@RequestMapping("/api/v1/reports/compensation")
@Tag(name = "Compensation analytics", description = "Current salary cost, by department and grade")
public class CompensationAnalyticsController {

    private final CompensationAnalyticsService analytics;

    public CompensationAnalyticsController(CompensationAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Compensation overview",
            description = """
                    What the organisation's current packages cost: organisation-wide
                    figures plus breakdowns by department and grade, each with its share
                    of the monthly gross.

                    This prices the packages in force now. It is not a payroll register —
                    it knows nothing about attendance or loss of pay, so it will differ
                    from an actual month's payroll wherever someone has unpaid days.
                    Active employees only; leavers cost nothing.""")
    public CompensationOverviewResponse overview() {
        return analytics.overview();
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Organisation-wide figures only, for a dashboard tile")
    public CompensationMetricsResponse summary() {
        return analytics.organisationMetrics();
    }

    @GetMapping("/by-department")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Salary cost per department, most expensive first")
    public List<CompensationGroupResponse> byDepartment() {
        return analytics.byDepartment();
    }

    @GetMapping("/by-grade")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Salary cost per grade, most expensive first")
    public List<CompensationGroupResponse> byGrade() {
        return analytics.byGrade();
    }
}
