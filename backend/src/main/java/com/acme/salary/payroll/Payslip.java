package com.acme.salary.payroll;

import com.acme.salary.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One employee's pay for one period.
 *
 * <p>Created with the draft run and never recomputed on finalisation (FR-5.8): finalising
 * publishes what was already reviewed, so the figures HR approved are the figures the
 * employee sees. A draft run's payslips are replaced wholesale when it is recomputed,
 * which is the only way they ever change (FR-5.6).
 *
 * <p>Read-only once its run is finalised (FR-6.6). Nothing here enforces that, and
 * nothing here should: immutability is a property of the run's state, so
 * {@link PayrollRun} refuses the recompute rather than each payslip refusing a setter.
 *
 * <p>The employee is held as an id, not an association. A payslip must resolve for an
 * employee who left three years ago, and coupling the payroll feature to the employee
 * aggregate would close the seam ADR-001 keeps open.
 */
@Entity
@Table(name = "payslips")
public class Payslip extends BaseEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "total_days", nullable = false)
    private int totalDays;

    @Column(name = "paid_days", nullable = false)
    private int paidDays;

    @Column(name = "lop_days", nullable = false)
    private int lopDays;

    @Column(name = "gross_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossPay;

    @Column(name = "total_deductions", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDeductions;

    @Column(name = "net_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPay;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /**
     * Lines are owned by the payslip: created with it, deleted with it, and never shared.
     * {@code orphanRemoval} is what lets a recompute discard the old lines by clearing the
     * collection.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "payslip_id", nullable = false)
    @OrderBy("sortOrder ASC")
    private List<PayslipLine> lines = new ArrayList<>();

    protected Payslip() {
        // for JPA
    }

    Payslip(Long employeeId, PayslipAmounts amounts) {
        this.employeeId = employeeId;
        apply(amounts);
    }

    /**
     * Replaces this payslip's figures and lines with a fresh computation.
     *
     * <p>Called only from {@link PayrollRun}, and only while the run is a draft.
     */
    void apply(PayslipAmounts amounts) {
        this.totalDays = amounts.totalDays();
        this.paidDays = amounts.paidDays();
        this.lopDays = amounts.lopDays();
        this.grossPay = amounts.grossPay();
        this.totalDeductions = amounts.totalDeductions();
        this.netPay = amounts.netPay();

        // Cleared rather than mutated in place: the component set can change between
        // recomputes, so matching lines up one by one would be more code and more ways to
        // be wrong than simply rebuilding them.
        this.lines.clear();
        for (PayslipAmounts.Line line : amounts.lines()) {
            this.lines.add(new PayslipLine(line));
        }
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public int getTotalDays() {
        return totalDays;
    }

    public int getPaidDays() {
        return paidDays;
    }

    public int getLopDays() {
        return lopDays;
    }

    public BigDecimal getGrossPay() {
        return grossPay;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getNetPay() {
        return netPay;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PayslipLine> getLines() {
        return List.copyOf(lines);
    }

    public List<PayslipLine> getEarnings() {
        return lines.stream().filter(PayslipLine::isEarning).toList();
    }

    public List<PayslipLine> getDeductions() {
        return lines.stream().filter(line -> !line.isEarning()).toList();
    }
}
