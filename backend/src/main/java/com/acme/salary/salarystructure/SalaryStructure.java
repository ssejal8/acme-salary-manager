package com.acme.salary.salarystructure;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.persistence.BaseEntity;
import com.acme.salary.salarycomponent.SalaryComponent;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One revision of an employee's compensation package, effective from a date (FR-4.1).
 *
 * <p><b>Append-only</b> (ADR-009). There is no method that changes a component or an
 * amount once the structure exists: a change is a new revision that supersedes this one,
 * so payroll for an earlier period still computes against the figures that were in force
 * then, and the history stays auditable. {@link #supersede} is the only state change, and
 * it records lifecycle dating rather than touching any figure — which is what keeps
 * FR-4.7 true even for a structure a finalised run has already used.
 *
 * <p>The employee is referenced by id, not by association: this feature reads what it
 * needs about an employee through the employee feature's service, keeping the aggregate
 * boundary intact (ADR-001). Component <em>definitions</em> are reference data and are
 * associated normally.
 */
@Entity
@Table(name = "salary_structures")
@EntityListeners(AuditingEntityListener.class)
public class SalaryStructure extends BaseEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "superseded_on")
    private LocalDate supersededOn;

    /** Required when the package falls outside the employee's grade band (FR-4.3). */
    @Column(name = "override_reason", length = 500, updatable = false)
    private String overrideReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(
            mappedBy = "structure",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<SalaryStructureComponent> components = new ArrayList<>();

    protected SalaryStructure() {
        // for JPA
    }

    public SalaryStructure(Long employeeId, LocalDate effectiveFrom, Long createdBy, String overrideReason) {
        this.employeeId = requireNonNull("employeeId", employeeId);
        this.effectiveFrom = requireNonNull("effectiveFrom", effectiveFrom);
        this.createdBy = requireNonNull("createdBy", createdBy);
        this.overrideReason = overrideReason == null || overrideReason.isBlank()
                ? null
                : overrideReason.strip();
    }

    /** Adds a line. Only valid while the structure is being built, before it is saved. */
    public void addComponent(SalaryComponent definition, BigDecimal value) {
        if (!isNew()) {
            // Belt and braces for ADR-009: a saved revision is never edited.
            throw new IllegalStateException("a persisted salary structure cannot be modified");
        }
        components.add(new SalaryStructureComponent(this, definition, definition.validateAssignedValue(value)));
    }

    /**
     * Closes this revision so a later one can take effect (FR-4.4).
     *
     * @param date the day the successor takes effect; must be after this revision's own
     *     effective date, which the database also enforces
     */
    public void supersede(LocalDate date) {
        LocalDate supersedeOn = requireNonNull("supersededOn", date);
        if (!supersedeOn.isAfter(effectiveFrom)) {
            throw ValidationException.field("effectiveFrom",
                    "must be after the current structure's effective date (%s)".formatted(effectiveFrom));
        }
        this.supersededOn = supersedeOn;
    }

    /** The lines as the calculator wants them: plain values, no entities. */
    public List<ComponentAmount> toComponentAmounts() {
        return components.stream().map(SalaryStructureComponent::toComponentAmount).toList();
    }

    public StructureTotals totals() {
        return SalaryStructureCalculator.compute(toComponentAmounts());
    }

    /** Whether this is the revision in force today, i.e. not yet superseded. */
    public boolean isCurrent() {
        return supersededOn == null;
    }

    /** Whether this revision governs pay on the given date. */
    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (supersededOn == null || date.isBefore(supersededOn));
    }

    private static <T> T requireNonNull(String field, T value) {
        if (value == null) {
            throw ValidationException.field(field, "is required");
        }
        return value;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getSupersededOn() {
        return supersededOn;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SalaryStructureComponent> getComponents() {
        return List.copyOf(components);
    }
}
