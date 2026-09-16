package com.acme.salary.salarystructure;

import com.acme.salary.common.persistence.BaseEntity;
import com.acme.salary.salarycomponent.SalaryComponent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One line of a salary structure: a component definition plus the figure agreed for this
 * employee.
 *
 * <p>The figure lives here rather than on the definition because two employees on the same
 * HRA component are on different amounts. For a percentage component the value is the
 * percentage, and the money is worked out at calculation time.
 */
@Entity
@Table(name = "salary_structure_components")
public class SalaryStructureComponent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "structure_id", nullable = false, updatable = false)
    private SalaryStructure structure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false, updatable = false)
    private SalaryComponent component;

    @Column(name = "value", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal value;

    protected SalaryStructureComponent() {
        // for JPA
    }

    SalaryStructureComponent(SalaryStructure structure, SalaryComponent component, BigDecimal value) {
        this.structure = structure;
        this.component = component;
        this.value = value;
    }

    ComponentAmount toComponentAmount() {
        return new ComponentAmount(
                component.getId(),
                component.getCode(),
                component.getName(),
                component.getType(),
                component.getCalculationType(),
                value);
    }

    public SalaryComponent getComponent() {
        return component;
    }

    public BigDecimal getValue() {
        return value;
    }
}
