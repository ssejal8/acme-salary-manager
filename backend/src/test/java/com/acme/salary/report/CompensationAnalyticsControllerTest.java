package com.acme.salary.report;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.common.money.Money;
import com.acme.salary.report.dto.CompensationGroupResponse;
import com.acme.salary.report.dto.CompensationMetricsResponse;
import com.acme.salary.report.dto.CompensationOverviewResponse;
import com.acme.salary.support.ApiSecurityTestConfig;
import com.acme.salary.support.ClockTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract and access control for compensation analytics. No database.
 *
 * <p>The access-control tests matter more here than on most endpoints: an aggregate over
 * salaries is not anonymous, so ADMIN/HR is the whole list of roles that may see any of
 * this (NFR-2.7).
 */
@WebMvcTest(controllers = CompensationAnalyticsController.class)
@Import(ApiSecurityTestConfig.class)
class CompensationAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompensationAnalyticsService analytics;

    private static CompensationMetricsResponse metrics(String gross) {
        return new CompensationMetricsResponse(
                12, 10, 2,
                Money.of(gross), Money.of("60000"), Money.of("540000"), Money.of("7200000"),
                Money.of("60000"), Money.of("55000"), Money.of("30000"), Money.of("120000"));
    }

    private static CompensationOverviewResponse overview() {
        return new CompensationOverviewResponse(
                ClockTestConfig.FIXED_INSTANT,
                metrics("600000"),
                List.of(new CompensationGroupResponse(
                        10L, "Engineering", metrics("480000"), Money.of("80.00"))),
                List.of(new CompensationGroupResponse(
                        30L, "G2", metrics("360000"), Money.of("60.00"))));
    }

    @Test
    @WithMockUser(roles = "HR")
    void overviewReportsOrganisationFiguresAndBothBreakdowns() throws Exception {
        when(analytics.overview()).thenReturn(overview());

        mockMvc.perform(get("/api/v1/reports/compensation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value("2026-09-16T09:00:00Z"))
                .andExpect(jsonPath("$.organisation.headcount").value(12))
                .andExpect(jsonPath("$.organisation.employeesWithoutPackage").value(2))
                .andExpect(jsonPath("$.byDepartment[0].groupName").value("Engineering"))
                .andExpect(jsonPath("$.byDepartment[0].shareOfMonthlyGross").value("80.00"))
                .andExpect(jsonPath("$.byGrade[0].groupName").value("G2"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void everyAmountIsAStringAtTwoDecimalPlaces() throws Exception {
        // ADR-006, and doubly so here: a client charting these must not parse them as
        // doubles.
        when(analytics.overview()).thenReturn(overview());

        mockMvc.perform(get("/api/v1/reports/compensation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organisation.totalMonthlyGross").value("600000.00"))
                .andExpect(jsonPath("$.organisation.totalAnnualCtc").value("7200000.00"))
                .andExpect(jsonPath("$.organisation.medianMonthlyGross").value("55000.00"))
                .andExpect(jsonPath("$.byDepartment[0].metrics.averageMonthlyGross").value("60000.00"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void summaryReturnsOrganisationFiguresAlone() throws Exception {
        when(analytics.organisationMetrics()).thenReturn(metrics("600000"));

        mockMvc.perform(get("/api/v1/reports/compensation/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMonthlyGross").value("600000.00"))
                .andExpect(jsonPath("$.byDepartment").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "HR")
    void byDepartmentReturnsTheGroupedRows() throws Exception {
        when(analytics.byDepartment()).thenReturn(overview().byDepartment());

        mockMvc.perform(get("/api/v1/reports/compensation/by-department"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value(10))
                .andExpect(jsonPath("$[0].metrics.totalMonthlyGross").value("480000.00"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void byGradeReturnsTheGroupedRows() throws Exception {
        when(analytics.byGrade()).thenReturn(overview().byGrade());

        mockMvc.perform(get("/api/v1/reports/compensation/by-grade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupName").value("G2"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMaySeeTheAnalytics() throws Exception {
        when(analytics.overview()).thenReturn(overview());

        mockMvc.perform(get("/api/v1/reports/compensation")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeMayNotSeeAggregatedSalaryData() throws Exception {
        // A department of one would disclose that person's salary exactly.
        mockMvc.perform(get("/api/v1/reports/compensation"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void everyAnalyticsPathIsClosedToEmployees() throws Exception {
        for (String path : List.of(
                "/api/v1/reports/compensation",
                "/api/v1/reports/compensation/summary",
                "/api/v1/reports/compensation/by-department",
                "/api/v1/reports/compensation/by-grade")) {
            mockMvc.perform(get(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    @WithAnonymousUser
    void anUnauthenticatedCallerGets401() throws Exception {
        mockMvc.perform(get("/api/v1/reports/compensation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }
}
