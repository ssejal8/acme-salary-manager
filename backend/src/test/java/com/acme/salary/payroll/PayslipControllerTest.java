package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.money.Money;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.payroll.dto.PayslipDetailResponse;
import com.acme.salary.payroll.dto.PayslipLineResponse;
import com.acme.salary.payroll.dto.PayslipRowResponse;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.support.ApiSecurityTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract and role gating for payslips. Ownership itself is the service's test. */
@WebMvcTest(controllers = PayslipController.class)
@Import(ApiSecurityTestConfig.class)
class PayslipControllerTest {

    private static final String PAYSLIPS = "/api/v1/payslips";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayslipService payslips;

    @Captor
    private ArgumentCaptor<PayslipSearch> searchCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private static PayslipDetailResponse payslip() {
        return new PayslipDetailResponse(
                90L, 2026, 4, "2026-04",
                PayrollRunStatus.FINALISED, true, Instant.parse("2026-05-01T10:00:00Z"),
                new PayslipDetailResponse.Employee(
                        1001L, "E-1001", "Asha Menon", "asha.menon@acme.test",
                        "Engineering", "Senior Software Engineer"),
                30, 25, 5,
                Money.of("125000.00"), Money.of("7700.00"), Money.of("117300.00"),
                "One Lakh Seventeen Thousand Three Hundred Rupees Only",
                List.of(new PayslipLineResponse("BASIC", "Basic Salary", ComponentType.EARNING,
                        Money.of("125000.00"))),
                List.of(new PayslipLineResponse("PF", "Provident Fund", ComponentType.DEDUCTION,
                        Money.of("7500.00"))));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeListsTheirOwnPayslips() throws Exception {
        when(payslips.myPayslips()).thenReturn(List.of(payslip()));

        mockMvc.perform(get(PAYSLIPS + "/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(90))
                .andExpect(jsonPath("$[0].period").value("2026-04"))
                .andExpect(jsonPath("$[0].employee.employeeCode").value("E-1001"))
                .andExpect(jsonPath("$[0].published").value(true));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void aPayslipNamesTheEmployeeAndThePeriod() throws Exception {
        // FR-6.2: it has to stand alone, so identity and period travel with it.
        when(payslips.findById(90L)).thenReturn(payslip());

        mockMvc.perform(get(PAYSLIPS + "/90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.fullName").value("Asha Menon"))
                .andExpect(jsonPath("$.employee.department").value("Engineering"))
                .andExpect(jsonPath("$.employee.designation").value("Senior Software Engineer"))
                .andExpect(jsonPath("$.periodYear").value(2026))
                .andExpect(jsonPath("$.periodMonth").value(4));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void reportsAttendanceAndEveryLine() throws Exception {
        when(payslips.findById(90L)).thenReturn(payslip());

        mockMvc.perform(get(PAYSLIPS + "/90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(30))
                .andExpect(jsonPath("$.paidDays").value(25))
                .andExpect(jsonPath("$.lopDays").value(5))
                .andExpect(jsonPath("$.earnings[0].code").value("BASIC"))
                .andExpect(jsonPath("$.deductions[0].code").value("PF"));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void sendsMoneyAsStringsAndNetPayInWords() throws Exception {
        // ADR-006 and FR-6.3.
        when(payslips.findById(90L)).thenReturn(payslip());

        mockMvc.perform(get(PAYSLIPS + "/90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grossPay").value("125000.00"))
                .andExpect(jsonPath("$.netPay").value("117300.00"))
                .andExpect(jsonPath("$.netPayInWords")
                        .value("One Lakh Seventeen Thousand Three Hundred Rupees Only"));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmptyListIsAnEmptyArrayNotA404() throws Exception {
        // Having no payslips yet is not an error.
        when(payslips.myPayslips()).thenReturn(List.of());

        mockMvc.perform(get(PAYSLIPS + "/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void aPayslipTheCallerMayNotHaveAnswers404() throws Exception {
        // The service refuses with NotFound rather than AccessDenied on purpose: a 403
        // would confirm the id exists.
        when(payslips.findById(90L)).thenThrow(NotFoundException.of("Payslip", 90L));

        mockMvc.perform(get(PAYSLIPS + "/90"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void theRefusalRevealsNothingAboutWhoseItIs() throws Exception {
        when(payslips.findById(90L)).thenThrow(NotFoundException.of("Payslip", 90L));

        String body = mockMvc.perform(get(PAYSLIPS + "/90"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("E-1001").doesNotContain("Asha").doesNotContain("1001");
    }

    /**
     * Every authenticated role reaches these endpoints, because "my own data" is not a
     * privilege to withhold — an HR user on the payroll has payslips too. Which *rows*
     * each of them may have is the service's decision.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "HR", "EMPLOYEE"})
    void everyAuthenticatedRoleMayAskForTheirOwn(String role) throws Exception {
        when(payslips.myPayslips()).thenReturn(List.of());

        // `user(...)` populates the SecurityContext, which is what Spring Security reads.
        // Setting the servlet request's principal instead leaves the context empty and
        // the request anonymous.
        mockMvc.perform(get(PAYSLIPS + "/me").with(user("someone@acme.test").roles(role)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HR")
    void hrMayOpenAPayslipById() throws Exception {
        when(payslips.findById(90L)).thenReturn(payslip());

        mockMvc.perform(get(PAYSLIPS + "/90")).andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void anUnauthenticatedCallerGets401() throws Exception {
        mockMvc.perform(get(PAYSLIPS + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get(PAYSLIPS + "/90"))
                .andExpect(status().isUnauthorized());
    }

    /** The paged search (FR-6.5), and the register when a period is given (FR-7.1). */
    @Nested
    @DisplayName("GET /payslips")
    class Searching {

        private PageResponse<PayslipRowResponse> onePage() {
            return new PageResponse<>(
                    List.of(new PayslipRowResponse(
                            90L, 7L, 2026, 4, "2026-04", PayrollRunStatus.FINALISED, true,
                            1001L, "E-1001", "Asha Menon", "Engineering",
                            30, 30, 0,
                            Money.of("150000.00"), Money.of("9200.00"), Money.of("140800.00"))),
                    0, 20, 1, 1, false, false);
        }

        @Test
        @WithMockUser(roles = "HR")
        void returnsThePublishedPageShape() throws Exception {
            when(payslips.search(any(), any())).thenReturn(onePage());

            mockMvc.perform(get("/api/v1/payslips"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].employeeName").value("Asha Menon"))
                    .andExpect(jsonPath("$.content[0].period").value("2026-04"))
                    .andExpect(jsonPath("$.content[0].netPay").value("140800.00"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        @WithMockUser(roles = "HR")
        void bindsEveryFilterOntoTheSearch() throws Exception {
            when(payslips.search(searchCaptor.capture(), any())).thenReturn(onePage());

            mockMvc.perform(get("/api/v1/payslips")
                            .param("runId", "7")
                            .param("periodYear", "2026")
                            .param("periodMonth", "4")
                            .param("departmentId", "3")
                            .param("employeeId", "1001"))
                    .andExpect(status().isOk());

            PayslipSearch search = searchCaptor.getValue();
            assertThat(search.runId()).isEqualTo(7L);
            assertThat(search.periodYear()).isEqualTo(2026);
            assertThat(search.periodMonth()).isEqualTo(4);
            assertThat(search.departmentId()).isEqualTo(3L);
            assertThat(search.employeeId()).isEqualTo(1001L);
            // Never set from a request parameter: the service decides it from the role.
            assertThat(search.publishedOnly()).isFalse();
        }

        @Test
        @WithMockUser(roles = "HR")
        void defaultsToNewestPeriodFirstWithATiebreaker() throws Exception {
            // Paging without a total order can repeat or skip rows between pages, and a
            // period is the same value for every row in a run.
            when(payslips.search(any(), pageableCaptor.capture())).thenReturn(onePage());

            mockMvc.perform(get("/api/v1/payslips")).andExpect(status().isOk());

            assertThat(pageableCaptor.getValue().getSort()).isEqualTo(Sort.by(
                    Sort.Order.desc("run.periodYear"),
                    Sort.Order.desc("run.periodMonth"),
                    Sort.Order.asc("employeeId")));
        }

        @Test
        @WithMockUser(roles = "HR")
        void translatesAWhitelistedSortKey() throws Exception {
            when(payslips.search(any(), pageableCaptor.capture())).thenReturn(onePage());

            mockMvc.perform(get("/api/v1/payslips").param("sort", "netPay,desc"))
                    .andExpect(status().isOk());

            assertThat(pageableCaptor.getValue().getSort())
                    .isEqualTo(Sort.by(Sort.Direction.DESC, "netPay"));
        }

        @Test
        @WithMockUser(roles = "HR")
        void rejectsAnUnlistedSortKey() throws Exception {
            // It would otherwise reach the query as a property path.
            mockMvc.perform(get("/api/v1/payslips").param("sort", "employee.workEmail,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
        }

        @Test
        @WithMockUser(roles = "HR")
        void capsAnOversizedPageRequest() throws Exception {
            when(payslips.search(any(), pageableCaptor.capture())).thenReturn(onePage());

            mockMvc.perform(get("/api/v1/payslips").param("size", "5000"))
                    .andExpect(status().isOk());

            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @WithMockUser(roles = "EMPLOYEE")
        void isOpenToAnEmployeeBecauseTheServiceNarrowsIt() throws Exception {
            // Reachable, but the service pins the query to their own published payslips —
            // the role check is the outer of two, as everywhere else here.
            when(payslips.search(any(), any())).thenReturn(onePage());

            mockMvc.perform(get("/api/v1/payslips")).andExpect(status().isOk());
        }

        @Test
        @WithAnonymousUser
        void needsAToken() throws Exception {
            mockMvc.perform(get("/api/v1/payslips")).andExpect(status().isUnauthorized());
        }
    }

    /** The PDF download (FR-6.4). */
    @Nested
    @DisplayName("GET /payslips/{id}/pdf")
    class DownloadPdf {

        @Test
        @WithMockUser(roles = "EMPLOYEE")
        void answersWithAPdfNamedAfterThePayslip() throws Exception {
            when(payslips.findById(90L)).thenReturn(payslip());

            byte[] body = mockMvc.perform(get("/api/v1/payslips/90/pdf"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"payslip-E-1001-2026-04.pdf\""))
                    .andReturn().getResponse().getContentAsByteArray();

            // A real document, not an empty body with the right headers.
            assertThat(new String(body, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(body.length).isGreaterThan(500);
        }

        @Test
        @WithMockUser(roles = "EMPLOYEE")
        void reusesTheOwnershipCheckRatherThanRestatingIt() throws Exception {
            // The same service call as the JSON read, so the 404-not-403 rule holds here
            // too: a payslip the caller may not have must not be confirmed to exist.
            when(payslips.findById(91L)).thenThrow(NotFoundException.of("Payslip", 91L));

            mockMvc.perform(get("/api/v1/payslips/91/pdf"))
                    .andExpect(status().isNotFound());

            verify(payslips).findById(91L);
        }

        @Test
        @WithAnonymousUser
        void needsAToken() throws Exception {
            mockMvc.perform(get("/api/v1/payslips/90/pdf"))
                    .andExpect(status().isUnauthorized());

            verify(payslips, never()).findById(any());
        }
    }
}
