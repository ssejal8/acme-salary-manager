package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.IllegalStateTransitionException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.employee.EmployeeCompensationContext;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.employee.EmployeeStatus;
import com.acme.salary.orgdata.CtcBand;
import com.acme.salary.payroll.dto.LopAdjustment;
import com.acme.salary.payroll.dto.PayrollRunDetailResponse;
import com.acme.salary.salarycomponent.SalaryComponent;
import com.acme.salary.salarystructure.SalaryStructure;
import com.acme.salary.salarystructure.SalaryStructureRepository;
import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import com.acme.salary.security.Role;
import com.acme.salary.support.EmployeeFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Orchestration: who a run includes, which revision governs them, and what the period
 * rules refuse. The arithmetic is {@code PayslipCalculatorTest}'s and the state machine is
 * {@code PayrollRunTest}'s — this covers the decisions in between.
 *
 * <p>The clock is fixed to May 2026, so April 2026 is a completed period and May is not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayrollRunServiceTest {

    private static final PayrollPeriod APRIL = PayrollPeriod.of(2026, 4);
    private static final Instant MID_MAY = Instant.parse("2026-05-15T09:00:00Z");
    private static final CurrentUser HR = new CurrentUser(2L, "hr@acme.test", Role.HR);

    @Mock
    private PayrollRunRepository runs;

    @Mock
    private EmployeeService employees;

    @Mock
    private SalaryStructureRepository structures;

    @Mock
    private CurrentUserProvider currentUser;

    @Mock
    private AuditService audit;

    @Captor
    private ArgumentCaptor<PayrollRun> savedRun;

    private PayrollRunService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(MID_MAY, ZoneOffset.UTC);
        service = new PayrollRunService(runs, employees, structures, currentUser, audit, clock);

        when(currentUser.require()).thenReturn(HR);
        when(runs.existsActiveForPeriod(anyInt(), anyInt())).thenReturn(false);
        // save() returns its argument, as a JPA repository does for an existing instance.
        when(runs.save(any(PayrollRun.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static EmployeeCompensationContext employee(long id, String code) {
        return employee(id, code, LocalDate.of(2022, 6, 1), null, EmployeeStatus.ACTIVE);
    }

    private static EmployeeCompensationContext employee(
            long id, String code, LocalDate joined, LocalDate exited, EmployeeStatus status) {
        return new EmployeeCompensationContext(
                id, code, code + " Person", joined, exited, status,
                10L, "Engineering", 30L, "G3", CtcBand.UNBOUNDED);
    }

    /** A structure whose components make a 50,000 gross with a 12% PF deduction. */
    private static SalaryStructure structureFor(long employeeId) {
        SalaryStructure structure =
                new SalaryStructure(employeeId, LocalDate.of(2022, 6, 1), 2L, null);
        structure.addComponent(basicDefinition(), new BigDecimal("50000.00"));
        structure.addComponent(pfDefinition(), new BigDecimal("12.00"));
        return EmployeeFixtures.withId(structure, 5000 + employeeId);
    }

    private static SalaryComponent basicDefinition() {
        return EmployeeFixtures.withId(
                new SalaryComponent("BASIC", "Basic Salary",
                        com.acme.salary.salarycomponent.ComponentType.EARNING,
                        com.acme.salary.salarycomponent.CalculationType.FLAT,
                        BigDecimal.ZERO, true),
                1L);
    }

    private static SalaryComponent pfDefinition() {
        return EmployeeFixtures.withId(
                new SalaryComponent("PF", "Provident Fund",
                        com.acme.salary.salarycomponent.ComponentType.DEDUCTION,
                        com.acme.salary.salarycomponent.CalculationType.PERCENT_OF_BASIC,
                        new BigDecimal("12.00"), false),
                5L);
    }

    /** Puts a cohort and their governing structures in front of the service. */
    private void cohortOf(long... employeeIds) {
        List<EmployeeCompensationContext> cohort = java.util.Arrays.stream(employeeIds)
                .mapToObj(id -> employee(id, "E-" + id))
                .toList();
        when(employees.payrollCohort(any(), any())).thenReturn(cohort);
        when(structures.findEffectiveOnForEmployees(anyCollection(), any()))
                .thenReturn(java.util.Arrays.stream(employeeIds)
                        .mapToObj(PayrollRunServiceTest::structureFor)
                        .toList());
    }

    @Nested
    class StartingARun {

        @Test
        void computesAPayslipForEveryIncludedEmployee() {
            cohortOf(1001L, 1002L);

            PayrollRunDetailResponse started = service.start(APRIL);

            assertThat(started.payslips()).hasSize(2);
            assertThat(started.run().status()).isEqualTo(PayrollRunStatus.DRAFT);
            assertThat(started.run().employeeCount()).isEqualTo(2);
        }

        @Test
        void resolvesTheRevisionGoverningTheLastDayOfThePeriod() {
            // FR-5.3. The last day, not today: a raise effective in May must not change
            // what April pays.
            cohortOf(1001L);

            service.start(APRIL);

            verify(structures).findEffectiveOnForEmployees(anyCollection(), eq(LocalDate.of(2026, 4, 30)));
            verify(employees).payrollCohort(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        }

        @Test
        void paysAFullMonthWhenNoLossOfPayIsRecorded() {
            cohortOf(1001L);

            PayrollRunDetailResponse started = service.start(APRIL);

            assertThat(started.payslips().get(0).grossPay()).isEqualByComparingTo("50000.00");
            assertThat(started.payslips().get(0).totalDeductions()).isEqualByComparingTo("6000.00");
            assertThat(started.payslips().get(0).netPay()).isEqualByComparingTo("44000.00");
            assertThat(started.payslips().get(0).paidDays()).isEqualTo(30);
        }

        @Test
        void readsTheCohortAndTheirStructuresOnceEach() {
            // Two queries for the whole run, not two per employee — the difference
            // between seconds and minutes at a thousand people (NFR-1.3).
            cohortOf(1001L, 1002L, 1003L);

            service.start(APRIL);

            verify(employees).payrollCohort(any(), any());
            verify(structures).findEffectiveOnForEmployees(anyCollection(), any());
        }

        @Test
        void skipsAnEligibleEmployeeWhoHoldsNoStructure() {
            // Counted by analytics as a coverage gap, and skipped here rather than given
            // a zero payslip (FR-5.2).
            when(employees.payrollCohort(any(), any()))
                    .thenReturn(List.of(employee(1001L, "E-1001"), employee(1010L, "E-1010")));
            when(structures.findEffectiveOnForEmployees(anyCollection(), any()))
                    .thenReturn(List.of(structureFor(1001L)));

            PayrollRunDetailResponse started = service.start(APRIL);

            assertThat(started.payslips()).hasSize(1);
            assertThat(started.payslips().get(0).employeeId()).isEqualTo(1001L);
        }

        @Test
        void recordsTheRunWithItsTotals() {
            cohortOf(1001L);

            service.start(APRIL);

            verify(audit).record(
                    eq(AuditEntityType.PAYROLL_RUN), any(), eq(AuditAction.PAYROLL_RUN_CREATED), any());
        }

        @Test
        void attributesTheRunToTheCallerWhoStartedIt() {
            cohortOf(1001L);

            service.start(APRIL);

            verify(runs).save(savedRun.capture());
            assertThat(savedRun.getValue().getInitiatedBy()).isEqualTo(HR.id());
        }
    }

    @Nested
    class WhatStartingRefuses {

        @Test
        void refusesASecondRunForAPeriodThatAlreadyHasOne() {
            // FR-5.7. The partial unique index is the real guard; this is the clean 409.
            cohortOf(1001L);
            when(runs.existsActiveForPeriod(2026, 4)).thenReturn(true);

            assertThatThrownBy(() -> service.start(APRIL))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("2026-04")
                    .hasMessageContaining("cancel it to run again");
            verify(runs, never()).save(any());
        }

        @Test
        void refusesAPeriodThatHasNotFinished() {
            // Proration divides by the days in the month, so running May on the 15th would
            // pay everyone a full month for a month that has not happened.
            cohortOf(1001L);

            assertThatThrownBy(() -> service.start(PayrollPeriod.of(2026, 5)))
                    .isInstanceOf(ValidationException.class);
            verify(runs, never()).save(any());
        }

        @Test
        void acceptsThePeriodThatHasJustEnded() {
            cohortOf(1001L);

            assertThat(service.start(PayrollPeriod.of(2026, 4)).payslips()).hasSize(1);
        }

        @Test
        void refusesARunWithNobodyPayable() {
            // An empty run would publish nothing and hold the period against a retry.
            when(employees.payrollCohort(any(), any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.start(APRIL))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("no employee is payable");
            verify(runs, never()).save(any());
        }

        @Test
        void refusesARunWhereNobodyEligibleHoldsAStructure() {
            when(employees.payrollCohort(any(), any())).thenReturn(List.of(employee(1010L, "E-1010")));
            when(structures.findEffectiveOnForEmployees(anyCollection(), any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.start(APRIL))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("nobody eligible holds a salary structure");
        }

        @Test
        void namesTheEmployeeWhenOneOfThemCannotBeComputed() {
            // The calculator reports against "components", which is useless on a run over
            // a thousand people. The employee code is what makes it actionable.
            SalaryStructure noBasic =
                    new SalaryStructure(1001L, LocalDate.of(2022, 6, 1), 2L, null);
            noBasic.addComponent(pfDefinition(), new BigDecimal("12.00"));
            when(employees.payrollCohort(any(), any())).thenReturn(List.of(employee(1001L, "E-1001")));
            when(structures.findEffectiveOnForEmployees(anyCollection(), any()))
                    .thenReturn(List.of(EmployeeFixtures.withId(noBasic, 5001L)));

            assertThatThrownBy(() -> service.start(APRIL))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("E-1001");
        }
    }

    @Nested
    class Recomputing {

        private PayrollRun existingDraft() {
            cohortOf(1001L, 1002L);
            PayrollRun run = new PayrollRun(APRIL, HR.id());
            run.computePayslips(new java.util.LinkedHashMap<>(java.util.Map.of(
                    1001L, PayslipCalculator.compute(structureFor(1001L).toComponentAmounts(), 0, 30),
                    1002L, PayslipCalculator.compute(structureFor(1002L).toComponentAmounts(), 0, 30))));
            when(runs.findWithPayslips(7L)).thenReturn(Optional.of(EmployeeFixtures.withId(run, 7L)));
            return run;
        }

        @Test
        void proratesTheAdjustedEmployeeAndLeavesTheRestAlone() {
            existingDraft();

            PayrollRunDetailResponse recomputed =
                    service.recompute(7L, List.of(new LopAdjustment(1001L, 15)));

            var adjusted = recomputed.payslips().stream()
                    .filter(p -> p.employeeId().equals(1001L)).findFirst().orElseThrow();
            var untouched = recomputed.payslips().stream()
                    .filter(p -> p.employeeId().equals(1002L)).findFirst().orElseThrow();

            assertThat(adjusted.lopDays()).isEqualTo(15);
            assertThat(adjusted.grossPay()).isEqualByComparingTo("25000.00");
            // 12% of the prorated 25,000 basic, not of 50,000 (FR-5.4).
            assertThat(adjusted.totalDeductions()).isEqualByComparingTo("3000.00");
            assertThat(untouched.lopDays()).isZero();
            assertThat(untouched.grossPay()).isEqualByComparingTo("50000.00");
        }

        @Test
        void treatsTheAdjustmentsAsTheWholePictureRatherThanADelta() {
            // How a mistaken adjustment is undone: recompute without it. As a delta there
            // would be no way to express "clear this LOP".
            existingDraft();
            service.recompute(7L, List.of(new LopAdjustment(1001L, 15)));

            PayrollRunDetailResponse cleared = service.recompute(7L, List.of());

            assertThat(cleared.payslips()).allSatisfy(payslip ->
                    assertThat(payslip.lopDays()).isZero());
        }

        @Test
        void updatesTheRunTotals() {
            existingDraft();

            PayrollRunDetailResponse recomputed =
                    service.recompute(7L, List.of(new LopAdjustment(1001L, 15)));

            assertThat(recomputed.run().totalGross()).isEqualByComparingTo("75000.00");
            assertThat(recomputed.run().totalNet())
                    .isEqualByComparingTo(recomputed.run().totalGross()
                            .subtract(recomputed.run().totalDeductions()));
        }

        @Test
        void rejectsAnAdjustmentForSomebodyNotOnTheRun() {
            // Almost always the wrong id. Ignoring it would leave HR believing an LOP had
            // been applied.
            existingDraft();

            assertThatThrownBy(() -> service.recompute(7L, List.of(new LopAdjustment(9999L, 3))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("not valid");
        }

        @Test
        void rejectsMoreLossOfPayDaysThanThePeriodHas() {
            existingDraft();

            assertThatThrownBy(() -> service.recompute(7L, List.of(new LopAdjustment(1001L, 31))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void rejectsTheSameEmployeeTwice() {
            existingDraft();

            assertThatThrownBy(() -> service.recompute(
                    7L, List.of(new LopAdjustment(1001L, 3), new LopAdjustment(1001L, 5))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesToRecomputeAFinalisedRun() {
            PayrollRun run = existingDraft();
            run.finalise(MID_MAY);

            assertThatThrownBy(() -> service.recompute(7L, List.of()))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void refusesAnUnknownRun() {
            when(runs.findWithPayslips(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.recompute(404L, List.of()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class FinalisingAndCancelling {

        private PayrollRun draftWithOnePayslip() {
            cohortOf(1001L);
            PayrollRun run = new PayrollRun(APRIL, HR.id());
            run.computePayslips(java.util.Map.of(
                    1001L, PayslipCalculator.compute(structureFor(1001L).toComponentAmounts(), 0, 30)));
            when(runs.findWithPayslips(7L)).thenReturn(Optional.of(EmployeeFixtures.withId(run, 7L)));
            return run;
        }

        @Test
        void finalisingPublishesAndStampsTheClock() {
            draftWithOnePayslip();

            PayrollRunDetailResponse finalised = service.finalise(7L);

            assertThat(finalised.run().status()).isEqualTo(PayrollRunStatus.FINALISED);
            assertThat(finalised.run().finalisedAt()).isEqualTo(MID_MAY);
        }

        @Test
        void finalisingDoesNotRecompute() {
            // FR-5.8, and the reason the review is worth anything.
            draftWithOnePayslip();

            service.finalise(7L);

            verify(employees, never()).payrollCohort(any(), any());
        }

        @Test
        void finalisingIsRecordedAgainstTheRun() {
            draftWithOnePayslip();

            service.finalise(7L);

            verify(audit).record(
                    eq(AuditEntityType.PAYROLL_RUN), eq(7L), eq(AuditAction.PAYROLL_RUN_FINALISED), any());
        }

        @Test
        void cancellingFreesThePeriodAndIsRecorded() {
            draftWithOnePayslip();

            PayrollRunDetailResponse cancelled = service.cancel(7L);

            assertThat(cancelled.run().status()).isEqualTo(PayrollRunStatus.CANCELLED);
            assertThat(cancelled.run().cancelledAt()).isEqualTo(MID_MAY);
            verify(audit).record(
                    eq(AuditEntityType.PAYROLL_RUN), eq(7L), eq(AuditAction.PAYROLL_RUN_CANCELLED), any());
        }

        @Test
        void refusesToFinaliseARunThatIsAlreadyTerminal() {
            PayrollRun run = draftWithOnePayslip();
            run.cancel(MID_MAY);

            assertThatThrownBy(() -> service.finalise(7L))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    @Test
    void readsOneRunWithItsPayslips() {
        cohortOf(1001L);
        PayrollRun run = new PayrollRun(APRIL, HR.id());
        run.computePayslips(java.util.Map.of(
                1001L, PayslipCalculator.compute(structureFor(1001L).toComponentAmounts(), 0, 30)));
        when(runs.findWithPayslips(7L)).thenReturn(Optional.of(EmployeeFixtures.withId(run, 7L)));

        PayrollRunDetailResponse found = service.findById(7L);

        assertThat(found.run().period()).isEqualTo("2026-04");
        assertThat(found.payslips()).hasSize(1);
    }

    @Test
    void refusesAnUnknownRun() {
        when(runs.findWithPayslips(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(404L)).isInstanceOf(NotFoundException.class);
    }
}
