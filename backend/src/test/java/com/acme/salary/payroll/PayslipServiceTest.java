package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.money.Money;
import com.acme.salary.employee.EmployeeIdentity;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.payroll.dto.PayslipDetailResponse;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.payroll.dto.PayslipRowResponse;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import com.acme.salary.security.Role;
import com.acme.salary.support.EmployeeFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Who may read which payslip.
 *
 * <p>This is the layer architecture §8.1 calls most often missed, so the cases here are
 * mostly about refusal: the employee who is not the subject, the subject whose payslip is
 * still a draft, and the shape of the answer they get.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayslipServiceTest {

    private static final CurrentUser ASHA = new CurrentUser(9L, "asha.menon@acme.test", Role.EMPLOYEE);
    private static final CurrentUser RAVI = new CurrentUser(11L, "ravi@acme.test", Role.EMPLOYEE);
    private static final CurrentUser HR = new CurrentUser(2L, "hr@acme.test", Role.HR);
    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@acme.test", Role.ADMIN);

    private static final long ASHA_EMPLOYEE_ID = 1001L;
    private static final long RAVI_EMPLOYEE_ID = 1002L;

    @Mock
    private PayslipRepository payslips;

    @Mock
    private EmployeeService employees;

    @Mock
    private CurrentUserProvider currentUser;

    private PayslipService service;

    @BeforeEach
    void setUp() {
        service = new PayslipService(payslips, employees, currentUser);
        when(employees.findSelf(ASHA.id())).thenReturn(Optional.of(identity(ASHA_EMPLOYEE_ID, "E-1001")));
        when(employees.findSelf(RAVI.id())).thenReturn(Optional.of(identity(RAVI_EMPLOYEE_ID, "E-1002")));
        // HR and ADMIN logins in the dev seed have no employee record.
        when(employees.findSelf(HR.id())).thenReturn(Optional.empty());
        when(employees.findSelf(ADMIN.id())).thenReturn(Optional.empty());
        when(employees.identityOf(anyLong())).thenReturn(identity(ASHA_EMPLOYEE_ID, "E-1001"));
    }

    private static EmployeeIdentity identity(long employeeId, String code) {
        return new EmployeeIdentity(
                employeeId, code, "Asha Menon", "asha.menon@acme.test",
                "Engineering", "Senior Software Engineer", "G3");
    }

    /** A payslip on a run in the given state, belonging to the given employee. */
    private static Payslip payslipFor(long employeeId, long payslipId, PayrollRunStatus status) {
        PayrollRun run = new PayrollRun(PayrollPeriod.of(2026, 4), 2L);
        run.computePayslips(java.util.Map.of(employeeId, amounts()));
        if (status == PayrollRunStatus.FINALISED) {
            run.finalise(Instant.parse("2026-05-01T10:00:00Z"));
        } else if (status == PayrollRunStatus.CANCELLED) {
            run.cancel(Instant.parse("2026-05-01T10:00:00Z"));
        }
        EmployeeFixtures.withId(run, 7L);
        Payslip payslip = run.payslipFor(employeeId).orElseThrow();
        return EmployeeFixtures.withId(payslip, payslipId);
    }

    private static PayslipAmounts amounts() {
        return new PayslipAmounts(
                30, 30, 0,
                Money.of("75000.00"), Money.of("150000.00"),
                Money.of("9200.00"), Money.of("140800.00"),
                List.of(
                        new PayslipAmounts.Line("BASIC", "Basic Salary", ComponentType.EARNING,
                                Money.of("150000.00"), 0),
                        new PayslipAmounts.Line("PF", "Provident Fund", ComponentType.DEDUCTION,
                                Money.of("9200.00"), 1)));
    }

    @Nested
    class MyOwnPayslips {

        @Test
        void returnsTheCallersPublishedPayslips() {
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findPublishedForEmployee(ASHA_EMPLOYEE_ID))
                    .thenReturn(List.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            List<PayslipDetailResponse> mine = service.myPayslips();

            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).employee().employeeCode()).isEqualTo("E-1001");
            assertThat(mine.get(0).period()).isEqualTo("2026-04");
            assertThat(mine.get(0).published()).isTrue();
        }

        @Test
        void asksOnlyForTheCallersOwnEmployeeId() {
            // The point of a /me path: nothing in the request names an employee, so there
            // is nothing to tamper with.
            when(currentUser.require()).thenReturn(ASHA);

            service.myPayslips();

            verify(payslips).findPublishedForEmployee(ASHA_EMPLOYEE_ID);
            verify(payslips, never()).findPublishedForEmployee(RAVI_EMPLOYEE_ID);
        }

        @Test
        void relaysTheRepositorysOrderingRatherThanReSorting() {
            // Newest period first is the query's job (FR-6.1); re-sorting here would be a
            // second ordering to keep in step.
            when(currentUser.require()).thenReturn(ASHA);
            Payslip april = payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED);
            Payslip march = payslipFor(ASHA_EMPLOYEE_ID, 89L, PayrollRunStatus.FINALISED);
            when(payslips.findPublishedForEmployee(ASHA_EMPLOYEE_ID)).thenReturn(List.of(april, march));

            assertThat(service.myPayslips()).extracting(PayslipDetailResponse::id)
                    .containsExactly(90L, 89L);
        }

        @Test
        void givesAnEmptyListToACallerWithNoEmployeeRecord() {
            // An ADMIN login provisioned without one genuinely has no payslips. An error
            // here would make signing in as admin look broken.
            when(currentUser.require()).thenReturn(ADMIN);

            assertThat(service.myPayslips()).isEmpty();
            verify(payslips, never()).findPublishedForEmployee(any());
        }

        @Test
        void givesAnEmptyListToAnEmployeeWithNoPublishedPayslipsYet() {
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findPublishedForEmployee(ASHA_EMPLOYEE_ID)).thenReturn(List.of());

            assertThat(service.myPayslips()).isEmpty();
        }

        @Test
        void leavesDraftFilteringToTheQuery() {
            // findPublishedForEmployee restricts to FINALISED runs, so no caller can
            // forget it. Verified by the repository test rather than re-filtered here.
            when(currentUser.require()).thenReturn(ASHA);

            service.myPayslips();

            verify(payslips).findPublishedForEmployee(ASHA_EMPLOYEE_ID);
        }
    }

    @Nested
    class ReadingOnePayslip {

        @Test
        void theSubjectMayReadTheirOwnOncePublished() {
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            PayslipDetailResponse found = service.findById(90L);

            assertThat(found.id()).isEqualTo(90L);
            assertThat(found.netPay()).isEqualByComparingTo("140800.00");
        }

        @Test
        void anEmployeeMayNotReadSomebodyElses() {
            // FR-1.5, and the case a role check alone cannot catch.
            when(currentUser.require()).thenReturn(RAVI);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            assertThatThrownBy(() -> service.findById(90L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusalLooksExactlyLikeNonExistence() {
            // A 403 would confirm the id exists, turning this endpoint into a way to probe
            // how many payslips there are and whose.
            // The same id both times, which is the only way the comparison means
            // anything: first the payslip exists and is refused, then it does not exist.
            // A caller must not be able to tell those two apart.
            when(currentUser.require()).thenReturn(RAVI);

            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));
            Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() -> service.findById(90L));

            when(payslips.findWithRunAndLines(90L)).thenReturn(Optional.empty());
            Throwable missing = org.assertj.core.api.Assertions.catchThrowable(() -> service.findById(90L));

            assertThat(refused).isInstanceOf(NotFoundException.class);
            assertThat(missing).isInstanceOf(NotFoundException.class);
            assertThat(refused.getMessage()).isEqualTo(missing.getMessage());
            assertThat(refused.getMessage()).isEqualTo(missing.getMessage()).doesNotContain("1001");
        }

        @Test
        void theSubjectMayNotReadTheirOwnWhileItIsStillADraft() {
            // FR-5.8: a draft payslip is not published and its figures may still change,
            // so being its subject is not yet grounds to see it.
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.DRAFT)));

            assertThatThrownBy(() -> service.findById(90L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void theSubjectMayNotReadOneFromACancelledRun() {
            // Never published, and never will be.
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.CANCELLED)));

            assertThatThrownBy(() -> service.findById(90L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void hrMayReadAnyonesPayslip() {
            when(currentUser.require()).thenReturn(HR);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            assertThat(service.findById(90L).id()).isEqualTo(90L);
        }

        @Test
        void hrMayReadADraftBecauseReviewingOneIsTheirJob() {
            // FR-5.6. The same row an employee is refused.
            when(currentUser.require()).thenReturn(HR);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.DRAFT)));

            PayslipDetailResponse found = service.findById(90L);

            assertThat(found.published()).isFalse();
            assertThat(found.runStatus()).isEqualTo(PayrollRunStatus.DRAFT);
        }

        @Test
        void adminMayReadAnyoneTo() {
            when(currentUser.require()).thenReturn(ADMIN);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            assertThat(service.findById(90L).id()).isEqualTo(90L);
        }

        @Test
        void anEmployeeWithNoRecordIsRefusedRatherThanCrashing() {
            // A token whose user has no employee row cannot own anything.
            CurrentUser orphan = new CurrentUser(99L, "orphan@acme.test", Role.EMPLOYEE);
            when(currentUser.require()).thenReturn(orphan);
            when(employees.findSelf(99L)).thenReturn(Optional.empty());
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            assertThatThrownBy(() -> service.findById(90L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusesAPayslipThatDoesNotExist() {
            when(currentUser.require()).thenReturn(HR);
            when(payslips.findWithRunAndLines(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(404L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void requiresAnAttributableCallerBeforeLoadingAnything() {
            // Every read has to be attributable, so the principal is resolved first.
            when(currentUser.require())
                    .thenThrow(new org.springframework.security.access.AccessDeniedException("no actor"));

            assertThatThrownBy(() -> service.findById(90L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verify(payslips, never()).findWithRunAndLines(any());
        }
    }

    @Nested
    class WhatAPayslipCarries {

        @Test
        void namesTheEmployeeAndThePeriodSoItStandsAlone() {
            // FR-6.2: a payslip has to identify who it is for and what it covers.
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            PayslipDetailResponse found = service.findById(90L);

            assertThat(found.employee().fullName()).isEqualTo("Asha Menon");
            assertThat(found.employee().department()).isEqualTo("Engineering");
            assertThat(found.employee().designation()).isEqualTo("Senior Software Engineer");
            assertThat(found.periodYear()).isEqualTo(2026);
            assertThat(found.periodMonth()).isEqualTo(4);
        }

        @Test
        void reportsAttendanceAndEveryLine() {
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            PayslipDetailResponse found = service.findById(90L);

            assertThat(found.totalDays()).isEqualTo(30);
            assertThat(found.paidDays()).isEqualTo(30);
            assertThat(found.lopDays()).isZero();
            assertThat(found.earnings()).extracting(line -> line.code()).containsExactly("BASIC");
            assertThat(found.deductions()).extracting(line -> line.code()).containsExactly("PF");
        }

        @Test
        void rendersNetPayInWords() {
            // FR-6.3, server-side so it cannot disagree with the figure beside it.
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            assertThat(service.findById(90L).netPayInWords())
                    .isEqualTo(Money.inWords(Money.of("140800.00")));
        }

        @Test
        void saysWhenItWasPublished() {
            when(currentUser.require()).thenReturn(ASHA);
            when(payslips.findWithRunAndLines(90L))
                    .thenReturn(Optional.of(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED)));

            assertThat(service.findById(90L).publishedAt())
                    .isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        }
    }

    /**
     * The paged search (FR-6.5, FR-7.1), where the role decides what the query may see.
     *
     * <p>These assert on the arguments reaching the repository rather than on filtered
     * output, because that is the whole design: restricting after paging would report
     * totals and page counts for rows the caller may not have.
     */
    @Nested
    @DisplayName("searching payslips")
    class Searching {

        private final Pageable pageable = PageRequest.of(0, 20);

        private void repositoryReturns(Payslip... rows) {
            when(payslips.search(any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(new PageImpl<>(List.of(rows), pageable, rows.length));
            when(employees.identitiesOf(any()))
                    .thenReturn(Map.of(ASHA_EMPLOYEE_ID, identity(ASHA_EMPLOYEE_ID, "E-1001")));
        }

        @Test
        void hrSearchesEveryPayslipIncludingDrafts() {
            when(currentUser.require()).thenReturn(HR);
            repositoryReturns(payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.DRAFT));

            PageResponse<PayslipRowResponse> found = service.search(
                    new PayslipSearch(null, 2026, 4, 3L, null, false), pageable);

            assertThat(found.content()).singleElement().satisfies(row -> {
                assertThat(row.employeeCode()).isEqualTo("E-1001");
                assertThat(row.employeeName()).isEqualTo("Asha Menon");
                assertThat(row.department()).isEqualTo("Engineering");
                assertThat(row.period()).isEqualTo("2026-04");
                assertThat(row.published()).isFalse();
            });
            verify(payslips).search(null, 2026, 4, 3L, null, false, pageable);
        }

        @Test
        void adminSearchesOnTheSameTerms() {
            when(currentUser.require()).thenReturn(ADMIN);
            repositoryReturns();

            service.search(PayslipSearch.forRun(7L), pageable);

            verify(payslips).search(7L, null, null, null, null, false, pageable);
        }

        @Test
        void anEmployeeIsPinnedToTheirOwnPublishedPayslips() {
            // Not a route around /me: whatever they ask for, the query is narrowed to
            // their own employee id and to finalised runs.
            when(currentUser.require()).thenReturn(ASHA);
            repositoryReturns();

            service.search(new PayslipSearch(null, null, null, null, RAVI_EMPLOYEE_ID, false), pageable);

            verify(payslips).search(null, null, null, null, ASHA_EMPLOYEE_ID, true, pageable);
        }

        @Test
        void aCallerWithNoEmployeeRecordGetsAnEmptyPageRatherThanEveryones() {
            // An EMPLOYEE login provisioned without a record. The id cannot match a row,
            // so the answer is a real empty page with real totals.
            when(currentUser.require())
                    .thenReturn(new CurrentUser(77L, "nobody@acme.test", Role.EMPLOYEE));
            when(employees.findSelf(77L)).thenReturn(Optional.empty());
            repositoryReturns();

            service.search(PayslipSearch.forRun(7L), pageable);

            verify(payslips).search(7L, null, null, null, -1L, true, pageable);
        }

        @Test
        void aMonthWithoutAYearIsNotAPeriodAndIsIgnored() {
            // "March" of no particular year would otherwise match every March on record.
            when(currentUser.require()).thenReturn(HR);
            repositoryReturns();

            service.search(new PayslipSearch(null, null, 3, null, null, false), pageable);

            verify(payslips).search(null, null, null, null, null, false, pageable);
        }

        @Test
        void resolvesNamesInOneBatchForThePageRatherThanPerRow() {
            // A query per row is the N+1 architecture 5.3 warns about.
            when(currentUser.require()).thenReturn(HR);
            repositoryReturns(
                    payslipFor(ASHA_EMPLOYEE_ID, 90L, PayrollRunStatus.FINALISED),
                    payslipFor(ASHA_EMPLOYEE_ID, 91L, PayrollRunStatus.FINALISED));

            service.search(PayslipSearch.forRun(7L), pageable);

            verify(employees, times(1)).identitiesOf(any());
            verify(employees, never()).identityOf(anyLong());
        }

        @Test
        void passesThePageRequestStraightToTheDatabase() {
            when(currentUser.require()).thenReturn(HR);
            repositoryReturns();
            Pageable requested = PageRequest.of(3, 50, Sort.by("netPay"));

            service.search(PayslipSearch.forRun(7L), requested);

            verify(payslips).search(7L, null, null, null, null, false, requested);
        }
    }
}
