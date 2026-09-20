package com.acme.salary.payroll;

import com.acme.salary.common.error.IllegalStateTransitionException;
import com.acme.salary.common.money.Money;
import com.acme.salary.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A month's payroll: the aggregate root for its payslips (FR-5.1, FR-5.5).
 *
 * <p>The state machine lives here rather than in the service, because every rule about
 * what may happen to a run is a rule about its state: a finalised run is immutable
 * (FR-5.5), only a draft may be recomputed (FR-5.6), and a cancelled run frees its period
 * for a retry (FR-5.7). Putting those checks in a service would let a second caller reach
 * the entity without them.
 *
 * <p>Payslips are owned by the run — created with it, cascaded from it, and replaced
 * wholesale on a recompute. The totals are derived from them on every change, never set
 * from outside, so {@code ck_payroll_runs_totals} ({@code total_net = total_gross -
 * total_deductions}) cannot be violated by a caller who forgets to keep them in step.
 */
@Entity
@Table(name = "payroll_runs")
public class PayrollRun extends BaseEntity {

    @Column(name = "period_year", nullable = false, updatable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false, updatable = false)
    private int periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayrollRunStatus status = PayrollRunStatus.DRAFT;

    @Column(name = "employee_count", nullable = false)
    private int employeeCount;

    @Column(name = "total_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalGross = Money.ZERO;

    @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDeductions = Money.ZERO;

    @Column(name = "total_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalNet = Money.ZERO;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private Long initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "finalised_at")
    private Instant finalisedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * The run owns its payslips, but the association is mapped from the child, because
     * that is where the foreign key is. {@code mappedBy} rather than a {@code @JoinColumn}
     * here is what lets a query reach a run from a payslip without loading every payslip
     * in it.
     */
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Payslip> payslips = new ArrayList<>();

    protected PayrollRun() {
        // for JPA
    }

    public PayrollRun(PayrollPeriod period, Long initiatedBy) {
        this.periodYear = period.year();
        this.periodMonth = period.month();
        this.initiatedBy = initiatedBy;
        this.status = PayrollRunStatus.DRAFT;
    }

    public PayrollPeriod period() {
        return PayrollPeriod.of(periodYear, periodMonth);
    }

    /**
     * Computes or recomputes every payslip in the run (FR-5.3, FR-5.6).
     *
     * <p>Existing payslips are updated in place where the employee is still in the cohort
     * and dropped where they are not, so a recompute after someone's exit date is
     * corrected does not leave them on the run. Identity is by employee, not by row.
     *
     * @param amountsByEmployee what each included employee is owed, already calculated
     * @throws IllegalStateTransitionException if the run is no longer a draft
     */
    public void computePayslips(Map<Long, PayslipAmounts> amountsByEmployee) {
        requireDraft("recomputed");

        Map<Long, Payslip> existing = new LinkedHashMap<>();
        for (Payslip payslip : payslips) {
            existing.put(payslip.getEmployeeId(), payslip);
        }

        List<Payslip> rebuilt = new ArrayList<>();
        for (Map.Entry<Long, PayslipAmounts> entry : amountsByEmployee.entrySet()) {
            Payslip payslip = existing.get(entry.getKey());
            if (payslip == null) {
                rebuilt.add(new Payslip(this, entry.getKey(), entry.getValue()));
            } else {
                payslip.apply(entry.getValue());
                rebuilt.add(payslip);
            }
        }

        // Cleared and refilled so orphanRemoval deletes anyone who dropped out of the
        // cohort. Reassigning the field would detach the collection Hibernate is tracking.
        payslips.clear();
        payslips.addAll(rebuilt);
        recalculateTotals();
    }

    /**
     * Publishes the run (FR-5.5, FR-5.8).
     *
     * <p>Nothing is recomputed: the payslips were computed with the draft and reviewed as
     * they stand, so finalising them unchanged is what makes the review meaningful.
     */
    public void finalise(Instant at) {
        requireDraft("finalised");
        if (payslips.isEmpty()) {
            // A run with nobody in it would publish nothing and occupy the period against
            // a retry, which is worse than refusing it.
            throw new IllegalStateTransitionException(
                    "payroll run for " + period().describe() + " has no payslips to publish");
        }
        this.status = PayrollRunStatus.FINALISED;
        this.finalisedAt = at;
    }

    /** Abandons the run, freeing the period for another attempt (FR-5.7, FR-6.6). */
    public void cancel(Instant at) {
        requireDraft("cancelled");
        this.status = PayrollRunStatus.CANCELLED;
        this.cancelledAt = at;
    }

    /**
     * The totals FR-5.10 reports, summed from the payslips.
     *
     * <p>Derived rather than accumulated: a recompute that changed one payslip would
     * otherwise have to adjust three running totals correctly, and the database's
     * {@code total_net = total_gross - total_deductions} check would catch only some of
     * the ways that goes wrong.
     */
    private void recalculateTotals() {
        this.employeeCount = payslips.size();
        this.totalGross = Money.sum(payslips.stream().map(Payslip::getGrossPay).toList());
        this.totalDeductions = Money.sum(payslips.stream().map(Payslip::getTotalDeductions).toList());
        this.totalNet = Money.normalize(totalGross.subtract(totalDeductions));
    }

    private void requireDraft(String attemptedAction) {
        if (status != PayrollRunStatus.DRAFT) {
            throw new IllegalStateTransitionException(
                    "payroll run for %s is %s and cannot be %s"
                            .formatted(period().describe(), status, attemptedAction));
        }
    }

    public Optional<Payslip> payslipFor(Long employeeId) {
        return payslips.stream()
                .filter(payslip -> payslip.getEmployeeId().equals(employeeId))
                .findFirst();
    }

    public boolean isDraft() {
        return status == PayrollRunStatus.DRAFT;
    }

    public boolean isFinalised() {
        return status == PayrollRunStatus.FINALISED;
    }

    public PayrollRunStatus getStatus() {
        return status;
    }

    public int getPeriodYear() {
        return periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public int getEmployeeCount() {
        return employeeCount;
    }

    public BigDecimal getTotalGross() {
        return totalGross;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getTotalNet() {
        return totalNet;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinalisedAt() {
        return finalisedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<Payslip> getPayslips() {
        return List.copyOf(payslips);
    }
}
