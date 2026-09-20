package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.IllegalStateTransitionException;
import com.acme.salary.common.money.Money;
import com.acme.salary.salarycomponent.ComponentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The run's state machine and its derived totals (FR-5.5, FR-5.10).
 *
 * <p>No database: the rules about what may happen to a run are the entity's, which is the
 * point of putting them there rather than in the service.
 */
class PayrollRunTest {

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final Long HR_USER = 2L;

    private static PayrollRun draftRun() {
        return new PayrollRun(PayrollPeriod.of(2026, 4), HR_USER);
    }

    /** A payslip's worth of amounts: one earning and one deduction, at full attendance. */
    private static PayslipAmounts amounts(String gross, String deductions) {
        BigDecimal grossAmount = Money.of(gross);
        BigDecimal deductionAmount = Money.of(deductions);
        return new PayslipAmounts(
                30, 30, 0,
                grossAmount,
                grossAmount,
                deductionAmount,
                Money.normalize(grossAmount.subtract(deductionAmount)),
                List.of(
                        new PayslipAmounts.Line("BASIC", "Basic Salary", ComponentType.EARNING, grossAmount, 0),
                        new PayslipAmounts.Line("PF", "Provident Fund", ComponentType.DEDUCTION, deductionAmount, 1)));
    }

    private static Map<Long, PayslipAmounts> cohort() {
        Map<Long, PayslipAmounts> computed = new LinkedHashMap<>();
        computed.put(1001L, amounts("90000.00", "5600.00"));
        computed.put(1002L, amounts("60000.00", "3400.00"));
        return computed;
    }

    @Nested
    class Totals {

        @Test
        void areSummedFromThePayslips() {
            PayrollRun run = draftRun();

            run.computePayslips(cohort());

            assertThat(run.getEmployeeCount()).isEqualTo(2);
            assertThat(run.getTotalGross()).isEqualByComparingTo("150000.00");
            assertThat(run.getTotalDeductions()).isEqualByComparingTo("9000.00");
            assertThat(run.getTotalNet()).isEqualByComparingTo("141000.00");
        }

        @Test
        void alwaysSatisfyTheDatabasesOwnInvariant() {
            // ck_payroll_runs_totals asserts total_net = total_gross - total_deductions.
            // Deriving the totals rather than accumulating them is what guarantees it.
            PayrollRun run = draftRun();

            run.computePayslips(cohort());

            assertThat(run.getTotalNet())
                    .isEqualByComparingTo(run.getTotalGross().subtract(run.getTotalDeductions()));
        }

        @Test
        void startAtZeroForAFreshRun() {
            PayrollRun run = draftRun();

            assertThat(run.getEmployeeCount()).isZero();
            assertThat(run.getTotalGross()).isEqualByComparingTo("0.00");
            assertThat(run.getTotalNet()).isEqualByComparingTo("0.00");
        }

        @Test
        void followARecomputeDownAsWellAsUp() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            Map<Long, PayslipAmounts> reduced = new LinkedHashMap<>();
            reduced.put(1001L, amounts("45000.00", "2800.00"));
            reduced.put(1002L, amounts("60000.00", "3400.00"));
            run.computePayslips(reduced);

            assertThat(run.getTotalGross()).isEqualByComparingTo("105000.00");
            assertThat(run.getTotalNet()).isEqualByComparingTo("98800.00");
        }
    }

    @Nested
    class Recomputing {

        @Test
        void updatesAnExistingPayslipRatherThanAddingASecond() {
            // Identity is by employee, not by row: a recompute must not leave an employee
            // with two payslips, which uq_payslips_run_employee would reject anyway.
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            run.computePayslips(Map.of(1001L, amounts("99000.00", "6000.00")));

            assertThat(run.getPayslips()).hasSize(1);
            assertThat(run.payslipFor(1001L)).isPresent();
            assertThat(run.payslipFor(1001L).orElseThrow().getGrossPay())
                    .isEqualByComparingTo("99000.00");
        }

        @Test
        void dropsAnEmployeeWhoIsNoLongerInTheCohort() {
            // Someone whose exit date was corrected between computations should leave the
            // run, not linger on it.
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            run.computePayslips(Map.of(1002L, amounts("60000.00", "3400.00")));

            assertThat(run.getPayslips()).hasSize(1);
            assertThat(run.payslipFor(1001L)).isEmpty();
            assertThat(run.getEmployeeCount()).isEqualTo(1);
        }

        @Test
        void addsAnEmployeeWhoHasBecomeEligible() {
            PayrollRun run = draftRun();
            run.computePayslips(Map.of(1001L, amounts("90000.00", "5600.00")));

            run.computePayslips(cohort());

            assertThat(run.getPayslips()).hasSize(2);
            assertThat(run.payslipFor(1002L)).isPresent();
        }

        @Test
        void replacesAPayslipsLinesRatherThanAppendingToThem() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            run.computePayslips(Map.of(1001L, amounts("45000.00", "2800.00")));

            // Two lines, not four. uq_payslip_lines_payslip_component would reject a
            // duplicated component code.
            assertThat(run.payslipFor(1001L).orElseThrow().getLines()).hasSize(2);
        }

        @Test
        void carriesTheDayCountsOntoThePayslip() {
            PayrollRun run = draftRun();
            PayslipAmounts withLop = new PayslipAmounts(
                    30, 25, 5, Money.of("50000.00"), Money.of("50000.00"),
                    Money.of("3000.00"), Money.of("47000.00"),
                    List.of(new PayslipAmounts.Line(
                            "BASIC", "Basic Salary", ComponentType.EARNING, Money.of("50000.00"), 0)));

            run.computePayslips(Map.of(1001L, withLop));

            Payslip payslip = run.payslipFor(1001L).orElseThrow();
            assertThat(payslip.getTotalDays()).isEqualTo(30);
            assertThat(payslip.getPaidDays()).isEqualTo(25);
            assertThat(payslip.getLopDays()).isEqualTo(5);
        }
    }

    @Nested
    class StateMachine {

        @Test
        void startsAsADraft() {
            assertThat(draftRun().getStatus()).isEqualTo(PayrollRunStatus.DRAFT);
            assertThat(draftRun().isDraft()).isTrue();
            assertThat(draftRun().isFinalised()).isFalse();
        }

        @Test
        void finalisingStampsTheTimeAndPublishes() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            run.finalise(NOW);

            assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.FINALISED);
            assertThat(run.getFinalisedAt()).isEqualTo(NOW);
            assertThat(run.getCancelledAt()).isNull();
        }

        @Test
        void finalisingDoesNotRecompute() {
            // FR-5.8: the figures reviewed are the figures published, which is what makes
            // the review meaningful.
            PayrollRun run = draftRun();
            run.computePayslips(cohort());
            BigDecimal reviewedNet = run.getTotalNet();

            run.finalise(NOW);

            assertThat(run.getTotalNet()).isEqualByComparingTo(reviewedNet);
            assertThat(run.getPayslips()).hasSize(2);
        }

        @Test
        void cancellingStampsItsOwnTime() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            run.cancel(NOW);

            assertThat(run.getStatus()).isEqualTo(PayrollRunStatus.CANCELLED);
            assertThat(run.getCancelledAt()).isEqualTo(NOW);
            assertThat(run.getFinalisedAt()).isNull();
        }

        @Test
        void aCancelledRunKeepsItsPayslipsAsARecord() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());

            run.cancel(NOW);

            assertThat(run.getPayslips()).hasSize(2);
        }

        @Test
        void refusesToFinaliseARunWithNobodyInIt() {
            // An empty run would publish nothing and hold the period against a retry.
            assertThatThrownBy(() -> draftRun().finalise(NOW))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("no payslips to publish");
        }

        @Test
        void cancellingAnEmptyRunIsFineBecauseItFreesThePeriod() {
            assertThatCode(() -> draftRun().cancel(NOW)).doesNotThrowAnyException();
        }
    }

    /**
     * A finalised run is immutable (FR-5.5), and a cancelled one is finished. Both are
     * terminal, so every mutation has to refuse.
     */
    @Nested
    class TerminalRunsRefuseEverything {

        @Test
        void aFinalisedRunCannotBeRecomputed() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());
            run.finalise(NOW);

            assertThatThrownBy(() -> run.computePayslips(cohort()))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("FINALISED")
                    .hasMessageContaining("recomputed");
        }

        @Test
        void aFinalisedRunCannotBeFinalisedAgainOrCancelled() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());
            run.finalise(NOW);

            assertThatThrownBy(() -> run.finalise(NOW))
                    .isInstanceOf(IllegalStateTransitionException.class);
            assertThatThrownBy(() -> run.cancel(NOW))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void aCancelledRunCannotBeRecomputedOrFinalised() {
            // A correction is a fresh run, not a revival of this one (FR-6.6).
            PayrollRun run = draftRun();
            run.computePayslips(cohort());
            run.cancel(NOW);

            assertThatThrownBy(() -> run.computePayslips(cohort()))
                    .isInstanceOf(IllegalStateTransitionException.class);
            assertThatThrownBy(() -> run.finalise(NOW))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void namesThePeriodAndTheStateSoTheMessageIsUseful() {
            PayrollRun run = draftRun();
            run.computePayslips(cohort());
            run.finalise(NOW);

            assertThatThrownBy(() -> run.cancel(NOW))
                    .hasMessageContaining("2026-04")
                    .hasMessageContaining("FINALISED");
        }

        @Test
        void bothTerminalStatesSaySoThemselves() {
            assertThat(PayrollRunStatus.DRAFT.isTerminal()).isFalse();
            assertThat(PayrollRunStatus.FINALISED.isTerminal()).isTrue();
            assertThat(PayrollRunStatus.CANCELLED.isTerminal()).isTrue();
        }
    }

    @Test
    void remembersItsPeriodAndWhoStartedIt() {
        PayrollRun run = draftRun();

        assertThat(run.period()).isEqualTo(PayrollPeriod.of(2026, 4));
        assertThat(run.getPeriodYear()).isEqualTo(2026);
        assertThat(run.getPeriodMonth()).isEqualTo(4);
        assertThat(run.getInitiatedBy()).isEqualTo(HR_USER);
    }
}
