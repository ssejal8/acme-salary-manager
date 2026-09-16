package com.acme.salary.salarycomponent;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.money.Money;
import com.acme.salary.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * The definition of a pay element — Basic, HRA, Provident Fund (FR-3.4).
 *
 * <p>A definition, not an amount: what an individual employee actually receives is set per
 * structure. The value held here is the default offered when a structure is assigned, and
 * for a percentage component it is the percentage itself.
 */
@Entity
@Table(name = "salary_components")
public class SalaryComponent extends AuditableEntity {

    /**
     * Every structure must include this component (FR-4.2), and percentage components are
     * computed against it, so the code is part of the domain rather than just data.
     */
    public static final String BASIC_CODE = "BASIC";

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    @Column(name = "code", nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private ComponentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_type", nullable = false, length = 30)
    private CalculationType calculationType;

    @Column(name = "default_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultValue;

    @Column(name = "taxable", nullable = false)
    private boolean taxable = true;

    /**
     * Retired components stay for the sake of historical structures and payslips
     * (ADR-014) but are not offered for new assignments.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected SalaryComponent() {
        // for JPA
    }

    public SalaryComponent(
            String code,
            String name,
            ComponentType type,
            CalculationType calculationType,
            BigDecimal defaultValue,
            boolean taxable) {
        this.code = requireCode(code);
        this.name = requireName(name);
        this.type = requireNonNull("type", type);
        this.calculationType = requireNonNull("calculationType", calculationType);
        this.defaultValue = requireValue(this.calculationType, defaultValue);
        this.taxable = taxable;
        this.active = true;
    }

    public void update(String name, CalculationType calculationType, BigDecimal defaultValue, boolean taxable) {
        this.name = requireName(name);
        this.calculationType = requireNonNull("calculationType", calculationType);
        this.defaultValue = requireValue(this.calculationType, defaultValue);
        this.taxable = taxable;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    /**
     * Validates a value a structure wants to use for this component — the same rules as
     * the default, applied to the per-employee figure.
     *
     * @return the value normalised to money scale
     */
    public BigDecimal validateAssignedValue(BigDecimal value) {
        return requireValue(calculationType, value);
    }

    public boolean isEarning() {
        return type == ComponentType.EARNING;
    }

    public boolean isDeduction() {
        return type == ComponentType.DEDUCTION;
    }

    public boolean isBasic() {
        return BASIC_CODE.equals(code);
    }

    public boolean isPercentageOfBasic() {
        return calculationType == CalculationType.PERCENT_OF_BASIC;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw ValidationException.field("code", "must not be blank");
        }
        String stripped = code.strip().toUpperCase();
        if (stripped.length() > 30) {
            throw ValidationException.field("code", "must be at most 30 characters");
        }
        if (!stripped.matches("[A-Z0-9_]+")) {
            // Codes appear on payslips and in exports, so they stay machine-friendly.
            throw ValidationException.field("code", "may contain only A-Z, 0-9 and underscore");
        }
        return stripped;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ValidationException.field("name", "must not be blank");
        }
        return name.strip();
    }

    private static BigDecimal requireValue(CalculationType calculationType, BigDecimal value) {
        if (value == null) {
            throw ValidationException.field("value", "is required");
        }
        BigDecimal normalised = Money.normalize(value);
        if (Money.isNegative(normalised)) {
            throw ValidationException.field("value", "must not be negative");
        }
        if (calculationType == CalculationType.PERCENT_OF_BASIC
                && normalised.compareTo(ONE_HUNDRED) > 0) {
            throw ValidationException.field("value", "must not exceed 100 for a percentage component");
        }
        return normalised;
    }

    private static <T> T requireNonNull(String field, T value) {
        if (value == null) {
            throw ValidationException.field(field, "is required");
        }
        return value;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ComponentType getType() {
        return type;
    }

    public CalculationType getCalculationType() {
        return calculationType;
    }

    public BigDecimal getDefaultValue() {
        return defaultValue;
    }

    public boolean isTaxable() {
        return taxable;
    }

    public boolean isActive() {
        return active;
    }
}
