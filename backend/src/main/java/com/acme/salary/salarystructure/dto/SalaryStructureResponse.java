package com.acme.salary.salarystructure.dto;

import com.acme.salary.salarystructure.ComputedComponent;
import com.acme.salary.salarystructure.SalaryStructure;
import com.acme.salary.salarystructure.StructureTotals;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One revision of an employee's compensation package, with its lines and totals.
 *
 * @param current whether this is the revision in force, i.e. not yet superseded
 * @param supersededOn the day a later revision took over, or null while this one stands
 * @param overrideReason present only where the package was accepted outside the grade band
 */
public record SalaryStructureResponse(
        Long id,
        Long employeeId,
        LocalDate effectiveFrom,
        LocalDate supersededOn,
        boolean current,
        String overrideReason,
        Instant createdAt,
        List<SalaryStructureComponentResponse> earnings,
        List<SalaryStructureComponentResponse> deductions,
        StructureTotalsResponse totals) {

    public static SalaryStructureResponse from(SalaryStructure structure) {
        return from(structure, structure.totals());
    }

    /** Overload for callers that have already computed the totals, to avoid doing it twice. */
    public static SalaryStructureResponse from(SalaryStructure structure, StructureTotals totals) {
        return new SalaryStructureResponse(
                structure.getId(),
                structure.getEmployeeId(),
                structure.getEffectiveFrom(),
                structure.getSupersededOn(),
                structure.isCurrent(),
                structure.getOverrideReason(),
                structure.getCreatedAt(),
                map(totals.earnings()),
                map(totals.deductions()),
                StructureTotalsResponse.from(totals));
    }

    private static List<SalaryStructureComponentResponse> map(List<ComputedComponent> components) {
        return components.stream().map(SalaryStructureComponentResponse::from).toList();
    }
}
