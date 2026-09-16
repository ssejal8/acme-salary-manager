package com.acme.salary.salarystructure.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to assign a compensation package to an employee (FR-4.1).
 *
 * <p>Assigning never edits the previous package; it supersedes it (ADR-009). The same
 * payload is accepted by the preview endpoint, which computes totals without saving
 * anything (FR-4.5).
 *
 * @param effectiveFrom the day this package takes effect; must not precede the employee's
 *     date of joining (FR-4.6)
 * @param overrideReason required only when the package falls outside the employee's grade
 *     CTC band (FR-4.3)
 */
public record AssignSalaryStructureRequest(
        @NotNull LocalDate effectiveFrom,
        @NotEmpty @Valid List<ComponentAssignment> components,
        @Size(max = 500) String overrideReason) {

    /**
     * @param value the monthly amount for a flat component, or the percentage for a
     *     percent-of-basic one
     */
    public record ComponentAssignment(
            @NotNull Long componentId,
            @NotNull BigDecimal value) {
    }
}
