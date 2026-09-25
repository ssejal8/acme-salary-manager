package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.IllegalStateTransitionException;
import com.acme.salary.common.money.Money;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.payroll.dto.LopAdjustment;
import com.acme.salary.payroll.dto.PayrollRunDetailResponse;
import com.acme.salary.payroll.dto.PayrollRunSummaryResponse;
import com.acme.salary.payroll.dto.PayslipLineResponse;
import com.acme.salary.payroll.dto.PayslipResponse;
import com.acme.salary.payroll.dto.PayslipRowResponse;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.support.ApiSecurityTestConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract and authorisation for payroll runs. No database. */
@WebMvcTest(controllers = PayrollRunController.class)
@Import(ApiSecurityTestConfig.class)
class PayrollRunControllerTest {

    private static final String RUNS = "/api/v1/payroll-runs";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayrollRunService runs;

    /**
     * The controller delegates its paged payslip endpoint to the payslip search rather
     * than reimplementing one, so this collaborator has to exist for the context to start.
     */
    @MockitoBean
    private PayslipService payslips;

    @Captor
    private ArgumentCaptor<PayrollPeriod> periodCaptor;

    @Captor
    private ArgumentCaptor<PayslipSearch> searchCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    @Captor
    private ArgumentCaptor<List<LopAdjustment>> adjustmentsCaptor;

    private static PayrollRunSummaryResponse summary(PayrollRunStatus status) {
        return new PayrollRunSummaryResponse(
                7L, 2026, 4, "2026-04", status, 2,
                Money.of("150000.00"), Money.of("9000.00"), Money.of("141000.00"),
                Instant.parse("2026-05-01T09:00:00Z"),
                status == PayrollRunStatus.FINALISED ? Instant.parse("2026-05-02T09:00:00Z") : null,
                status == PayrollRunStatus.CANCELLED ? Instant.parse("2026-05-02T09:00:00Z") : null);
    }

    private static PayrollRunDetailResponse detail(PayrollRunStatus status) {
        return new PayrollRunDetailResponse(summary(status), List.of(payslip()));
    }

    private static PayslipResponse payslip() {
        return new PayslipResponse(
                90L, 1001L, 30, 25, 5,
                Money.of("75000.00"), Money.of("4700.00"), Money.of("70300.00"),
                "Seventy Thousand Three Hundred Rupees Only",
                List.of(new PayslipLineResponse("BASIC", "Basic Salary", ComponentType.EARNING,
                        Money.of("75000.00"))),
                List.of(new PayslipLineResponse("PF", "Provident Fund", ComponentType.DEDUCTION,
                        Money.of("4500.00"))));
    }

    private static final String START_BODY = """
            {"periodYear": 2026, "periodMonth": 4}
            """;

    @Test
    @WithMockUser(roles = "HR")
    void startingARunAnswers201WithItsLocation() throws Exception {
        when(runs.start(any())).thenReturn(detail(PayrollRunStatus.DRAFT));

        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/payroll-runs/7"))
                .andExpect(jsonPath("$.run.period").value("2026-04"))
                .andExpect(jsonPath("$.run.status").value("DRAFT"))
                .andExpect(jsonPath("$.payslips[0].employeeId").value(1001));
    }

    @Test
    @WithMockUser(roles = "HR")
    void bindsThePeriodOntoTheValueObject() throws Exception {
        when(runs.start(any())).thenReturn(detail(PayrollRunStatus.DRAFT));

        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isCreated());

        verify(runs).start(periodCaptor.capture());
        assertThat(periodCaptor.getValue()).isEqualTo(PayrollPeriod.of(2026, 4));
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAnImpossibleMonthAsAFieldErrorNotAConflict() throws Exception {
        // Bean Validation catches it before the service, so it is a 400 naming the field
        // rather than a database constraint surfacing later.
        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodYear": 2026, "periodMonth": 13}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("periodMonth"));

        verify(runs, never()).start(any());
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAYearOutsideTheSchemasRange() throws Exception {
        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodYear": 1999, "periodMonth": 4}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("periodYear"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAMissingPeriod() throws Exception {
        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(runs, never()).start(any());
    }

    @Test
    @WithMockUser(roles = "HR")
    void aSecondRunForThePeriodAnswers409() throws Exception {
        // FR-5.7 precisely.
        when(runs.start(any())).thenThrow(
                new ConflictException("a payroll run for 2026-04 already exists"));

        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("a payroll run for 2026-04 already exists"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void listsRunsInThePublishedPageShapeWithoutPayslips() throws Exception {
        // A list of twelve months must not carry twelve thousand payslips (NFR-2.7).
        when(runs.list(any())).thenReturn(
                new PageResponse<>(List.of(summary(PayrollRunStatus.FINALISED)), 0, 20, 1, 1, false, false));

        String body = mockMvc.perform(get(RUNS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].period").value("2026-04"))
                .andExpect(jsonPath("$.content[0].totalNet").value("141000.00"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("payslips");
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAnUnlistedSortKey() throws Exception {
        mockMvc.perform(get(RUNS).param("sort", "initiatedBy,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void getsOneRunWithEveryPayslipInIt() throws Exception {
        when(runs.findById(7L)).thenReturn(detail(PayrollRunStatus.DRAFT));

        mockMvc.perform(get(RUNS + "/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslips[0].paidDays").value(25))
                .andExpect(jsonPath("$.payslips[0].lopDays").value(5))
                .andExpect(jsonPath("$.payslips[0].earnings[0].code").value("BASIC"))
                .andExpect(jsonPath("$.payslips[0].deductions[0].code").value("PF"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void sendsMoneyAsStringsAndNetPayInWords() throws Exception {
        // ADR-006 for the amounts, FR-6.3 for the words — rendered server-side so they
        // cannot disagree with the figure beside them.
        when(runs.findById(7L)).thenReturn(detail(PayrollRunStatus.DRAFT));

        mockMvc.perform(get(RUNS + "/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslips[0].netPay").value("70300.00"))
                .andExpect(jsonPath("$.payslips[0].netPayInWords")
                        .value("Seventy Thousand Three Hundred Rupees Only"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void recomputePassesTheAdjustmentsThrough() throws Exception {
        when(runs.recompute(eq(7L), any())).thenReturn(detail(PayrollRunStatus.DRAFT));

        mockMvc.perform(post(RUNS + "/7/recompute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adjustments": [{"employeeId": 1001, "lopDays": 5}]}
                                """))
                .andExpect(status().isOk());

        verify(runs).recompute(eq(7L), adjustmentsCaptor.capture());
        assertThat(adjustmentsCaptor.getValue()).containsExactly(new LopAdjustment(1001L, 5));
    }

    @Test
    @WithMockUser(roles = "HR")
    void recomputeAcceptsAnEmptyAdjustmentList() throws Exception {
        // Meaningful rather than pointless: it clears every adjustment.
        when(runs.recompute(eq(7L), any())).thenReturn(detail(PayrollRunStatus.DRAFT));

        mockMvc.perform(post(RUNS + "/7/recompute").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(runs).recompute(eq(7L), adjustmentsCaptor.capture());
        assertThat(adjustmentsCaptor.getValue()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsNegativeLossOfPayDays() throws Exception {
        mockMvc.perform(post(RUNS + "/7/recompute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adjustments": [{"employeeId": 1001, "lopDays": -3}]}
                                """))
                .andExpect(status().isBadRequest());

        verify(runs, never()).recompute(any(), any());
    }

    @Test
    @WithMockUser(roles = "HR")
    void recomputingAFinalisedRunAnswers409() throws Exception {
        // FR-5.5: a finalised run is immutable.
        when(runs.recompute(eq(7L), any())).thenThrow(
                new IllegalStateTransitionException("payroll run for 2026-04 is FINALISED"));

        mockMvc.perform(post(RUNS + "/7/recompute").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @WithMockUser(roles = "HR")
    void finalisingPublishesTheRun() throws Exception {
        when(runs.finalise(7L)).thenReturn(detail(PayrollRunStatus.FINALISED));

        mockMvc.perform(post(RUNS + "/7/finalise"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("FINALISED"))
                .andExpect(jsonPath("$.run.finalisedAt").exists());
    }

    @Test
    @WithMockUser(roles = "HR")
    void cancellingFreesThePeriod() throws Exception {
        when(runs.cancel(7L)).thenReturn(detail(PayrollRunStatus.CANCELLED));

        mockMvc.perform(post(RUNS + "/7/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CANCELLED"))
                .andExpect(jsonPath("$.run.cancelledAt").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayRunPayrollToo() throws Exception {
        when(runs.list(any())).thenReturn(
                new PageResponse<>(List.of(), 0, 20, 0, 0, false, false));

        mockMvc.perform(get(RUNS)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeCannotSeeAnyOfIt() throws Exception {
        // A draft run holds every salary in the organisation (NFR-2.7). An employee's own
        // payslips are a different endpoint with an ownership check (FR-1.5, FR-6.1).
        mockMvc.perform(get(RUNS)).andExpect(status().isForbidden());
        mockMvc.perform(get(RUNS + "/7")).andExpect(status().isForbidden());
        mockMvc.perform(post(RUNS).contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(RUNS + "/7/finalise")).andExpect(status().isForbidden());
        mockMvc.perform(post(RUNS + "/7/cancel")).andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void anUnauthenticatedCallerGets401() throws Exception {
        mockMvc.perform(get(RUNS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /** The paged payslip endpoint, which is the register for the run's period (FR-7.1). */
    @Nested
    @DisplayName("GET /payroll-runs/{id}/payslips")
    class RunPayslips {

        private PageResponse<PayslipRowResponse> onePage() {
            return new PageResponse<>(
                    List.of(new PayslipRowResponse(
                            90L, 7L, 2026, 4, "2026-04", PayrollRunStatus.DRAFT, false,
                            1001L, "E-1001", "Asha Menon", "Engineering",
                            30, 28, 2,
                            Money.of("140000.00"), Money.of("9200.00"), Money.of("130800.00"))),
                    0, 25, 1, 1, false, false);
        }

        @Test
        @WithMockUser(roles = "HR")
        void returnsRowsCarryingTheEmployeeName() throws Exception {
            // The name a payslip cannot supply for itself: it holds an employee id, so the
            // service resolves identities for the page.
            when(payslips.search(any(), any())).thenReturn(onePage());

            mockMvc.perform(get(RUNS + "/7/payslips"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].employeeCode").value("E-1001"))
                    .andExpect(jsonPath("$.content[0].employeeName").value("Asha Menon"))
                    .andExpect(jsonPath("$.content[0].department").value("Engineering"))
                    .andExpect(jsonPath("$.content[0].netPay").value("130800.00"))
                    .andExpect(jsonPath("$.content[0].published").value(false))
                    .andExpect(jsonPath("$.size").value(25));
        }

        @Test
        @WithMockUser(roles = "HR")
        void searchesOnlyThatRun() throws Exception {
            when(payslips.search(searchCaptor.capture(), pageableCaptor.capture()))
                    .thenReturn(onePage());

            mockMvc.perform(get(RUNS + "/7/payslips").param("page", "2").param("size", "10"))
                    .andExpect(status().isOk());

            assertThat(searchCaptor.getValue().runId()).isEqualTo(7L);
            assertThat(searchCaptor.getValue().periodYear()).isNull();
            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        }

        @Test
        @WithMockUser(roles = "HR")
        void pagesInsteadOfReturningTheWholeRun() throws Exception {
            // Why this endpoint exists: GET /payroll-runs/{id} carries every payslip and
            // its lines, which at ten thousand employees is tens of megabytes.
            when(payslips.search(any(), pageableCaptor.capture())).thenReturn(onePage());

            mockMvc.perform(get(RUNS + "/7/payslips")).andExpect(status().isOk());

            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
        }

        @Test
        @WithMockUser(roles = "EMPLOYEE")
        void isNotReachableByAnEmployee() throws Exception {
            mockMvc.perform(get(RUNS + "/7/payslips")).andExpect(status().isForbidden());
        }

        @Test
        @WithAnonymousUser
        void needsAToken() throws Exception {
            mockMvc.perform(get(RUNS + "/7/payslips")).andExpect(status().isUnauthorized());
        }
    }
}
