package com.acme.salary.payroll;

import com.acme.salary.common.persistence.BaseEntity;
import com.acme.salary.salarycomponent.ComponentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One earning or deduction on a published payslip.
 *
 * <p>The component's code, name and type are <em>copied</em> here rather than referenced.
 * That is ADR-010: a payslip must render exactly as it was published, so renaming
 * "Conveyance Allowance" next year must not retitle a line on last year's payslip. The
 * cost is a denormalised column and no foreign key back to the definition; the benefit is
 * that a published figure and its label can never drift.
 */
@Entity
@Table(name = "payslip_lines")
public class PayslipLine extends BaseEntity {

    @Column(name = "component_code", nullable = false, length = 30, updatable = false)
    private String componentCode;

    @Column(name = "component_name", nullable = false, length = 120, updatable = false)
    private String componentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private ComponentType type;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    protected PayslipLine() {
        // for JPA
    }

    PayslipLine(PayslipAmounts.Line line) {
        this.componentCode = line.code();
        this.componentName = line.name();
        this.type = line.type();
        this.amount = line.amount();
        this.sortOrder = line.sortOrder();
    }

    public String getComponentCode() {
        return componentCode;
    }

    public String getComponentName() {
        return componentName;
    }

    public ComponentType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isEarning() {
        return type == ComponentType.EARNING;
    }
}
