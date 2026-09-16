package com.acme.salary.salarystructure;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ApiError;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.employee.EmployeeCompensationContext;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.salarycomponent.SalaryComponent;
import com.acme.salary.salarycomponent.SalaryComponentRepository;
import com.acme.salary.salarystructure.dto.AssignSalaryStructureRequest;
import com.acme.salary.salarystructure.dto.AssignSalaryStructureRequest.ComponentAssignment;
import com.acme.salary.salarystructure.dto.SalaryStructureResponse;
import com.acme.salary.salarystructure.dto.StructureTotalsResponse;
import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigning and reading compensation packages.
 *
 * <p>Every rule that needs more than one fact lives here: the effective date against the
 * employee's joining date (FR-4.6), the package against the grade band (FR-4.3), the new
 * revision against the one it supersedes (FR-4.4). The arithmetic itself belongs to
 * {@link SalaryStructureCalculator}, which knows nothing about employees or dates.
 *
 * <p>There is no update and no delete, by design (ADR-009): a change is a new revision.
 * That is also what makes FR-4.7 hold — a structure a finalised payroll run has used
 * cannot be altered, because nothing can alter any structure.
 */
@Service
public class SalaryStructureService {

    private final SalaryStructureRepository structures;
    private final SalaryComponentRepository components;
    private final EmployeeService employees;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public SalaryStructureService(
            SalaryStructureRepository structures,
            SalaryComponentRepository components,
            EmployeeService employees,
            CurrentUserProvider currentUser,
            AuditService audit) {
        this.structures = structures;
        this.components = components;
        this.employees = employees;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** Full revision history for an employee, newest first (FR-4.4). */
    @Transactional(readOnly = true)
    public List<SalaryStructureResponse> history(Long employeeId) {
        employees.compensationContext(employeeId); // 404s for an unknown employee
        return structures.findHistory(employeeId).stream()
                .map(SalaryStructureResponse::from)
                .toList();
    }

    /** The package in force, if the employee has one yet. */
    @Transactional(readOnly = true)
    public Optional<SalaryStructureResponse> current(Long employeeId) {
        employees.compensationContext(employeeId);
        return structures.findCurrent(employeeId).map(SalaryStructureResponse::from);
    }

    /**
     * Computes what a package would come to, and validates it, without saving anything
     * (FR-4.5).
     *
     * <p>Everything the assignment would reject is rejected here too, so the form can show
     * the problem before anyone commits to it — the same code path, not a parallel one.
     */
    @Transactional(readOnly = true)
    public StructureTotalsResponse preview(Long employeeId, AssignSalaryStructureRequest request) {
        EmployeeCompensationContext employee = employees.compensationContext(employeeId);
        StructureTotals totals = SalaryStructureCalculator.compute(
                toComponentAmounts(resolveComponents(request.components()), request.components()));
        validateEffectiveDate(employee, request);
        validateGradeBand(employee, totals, request.overrideReason());
        return StructureTotalsResponse.from(totals);
    }

    /**
     * Assigns a package, superseding the current one (FR-4.1, FR-4.4).
     *
     * <p>Both writes — closing the old revision and creating the new one — happen in this
     * transaction along with the audit event, so an employee can never be left with two
     * open revisions or a raise with no record of who granted it.
     */
    @Transactional
    public SalaryStructureResponse assign(Long employeeId, AssignSalaryStructureRequest request) {
        EmployeeCompensationContext employee = employees.compensationContext(employeeId);
        CurrentUser actor = currentUser.require();

        Map<Long, SalaryComponent> definitions = resolveComponents(request.components());
        StructureTotals totals = SalaryStructureCalculator.compute(
                toComponentAmounts(definitions, request.components()));

        validateEffectiveDate(employee, request);
        validateGradeBand(employee, totals, request.overrideReason());

        if (structures.existsByEmployeeIdAndEffectiveFrom(employeeId, request.effectiveFrom())) {
            throw new ConflictException(
                    "A salary structure already takes effect on " + request.effectiveFrom()
                            + " for this employee");
        }

        structures.findCurrent(employeeId).ifPresent(existing -> {
            existing.supersede(request.effectiveFrom());
            audit.record(AuditEntityType.SALARY_STRUCTURE, existing.getId(),
                    AuditAction.SALARY_STRUCTURE_SUPERSEDED,
                    Map.of("employeeId", employeeId, "supersededOn", request.effectiveFrom().toString()));
        });

        SalaryStructure structure = new SalaryStructure(
                employeeId, request.effectiveFrom(), actor.id(), request.overrideReason());
        for (ComponentAssignment assignment : request.components()) {
            structure.addComponent(definitions.get(assignment.componentId()), assignment.value());
        }

        SalaryStructure saved = structures.save(structure);
        audit.record(AuditEntityType.SALARY_STRUCTURE, saved.getId(),
                AuditAction.SALARY_STRUCTURE_ASSIGNED, auditDetails(employeeId, request, totals));
        return SalaryStructureResponse.from(saved, totals);
    }

    /**
     * Loads every referenced definition in one query and rejects unknown or retired ones.
     *
     * <p>Retired components are refused for a <em>new</em> package while remaining valid
     * on the historical packages that already use them (ADR-014).
     */
    private Map<Long, SalaryComponent> resolveComponents(List<ComponentAssignment> assignments) {
        List<Long> ids = assignments.stream().map(ComponentAssignment::componentId).distinct().toList();
        Map<Long, SalaryComponent> byId = new LinkedHashMap<>();
        components.findAllByIdIn(ids).forEach(component -> byId.put(component.getId(), component));

        List<ApiError.FieldError> problems = new ArrayList<>();
        for (Long id : ids) {
            SalaryComponent component = byId.get(id);
            if (component == null) {
                problems.add(new ApiError.FieldError("components", "component " + id + " does not exist"));
            } else if (!component.isActive()) {
                problems.add(new ApiError.FieldError("components",
                        "component " + component.getCode() + " is no longer active"));
            }
        }
        if (ids.size() != assignments.size()) {
            problems.add(new ApiError.FieldError("components", "a component is listed more than once"));
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("Salary structure is not valid", problems);
        }
        return byId;
    }

    private List<ComponentAmount> toComponentAmounts(
            Map<Long, SalaryComponent> definitions, List<ComponentAssignment> assignments) {
        return assignments.stream()
                .map(assignment -> {
                    SalaryComponent definition = definitions.get(assignment.componentId());
                    return new ComponentAmount(
                            definition.getId(),
                            definition.getCode(),
                            definition.getName(),
                            definition.getType(),
                            definition.getCalculationType(),
                            definition.validateAssignedValue(assignment.value()));
                })
                .toList();
    }

    private void validateEffectiveDate(
            EmployeeCompensationContext employee, AssignSalaryStructureRequest request) {
        if (request.effectiveFrom().isBefore(employee.dateOfJoining())) {
            throw ValidationException.field("effectiveFrom",
                    "must not precede the employee's date of joining (%s)".formatted(employee.dateOfJoining()));
        }
        if (!employee.isActive()) {
            // A leaver's package is history; changing it would rewrite what they were paid.
            throw ValidationException.field("effectiveFrom",
                    "employee %s has left and their compensation cannot be changed"
                            .formatted(employee.employeeCode()));
        }
    }

    /**
     * Grade band check (FR-4.3). Outside the band is allowed, but only deliberately: the
     * caller must say why, and the reason is stored on the revision and in the audit
     * trail.
     */
    private void validateGradeBand(
            EmployeeCompensationContext employee, StructureTotals totals, String overrideReason) {
        boolean hasReason = overrideReason != null && !overrideReason.isBlank();
        if (!employee.gradeBand().isConfigured() || hasReason) {
            return;
        }
        BigDecimal annualCtc = totals.annualCtc();
        if (!employee.gradeBand().contains(annualCtc)) {
            throw ValidationException.field("overrideReason",
                    "annual CTC %s falls outside grade %s's band (%s); supply an override reason to proceed"
                            .formatted(annualCtc.toPlainString(), employee.gradeName(),
                                    employee.gradeBand().describe()));
        }
    }

    private Map<String, Object> auditDetails(
            Long employeeId, AssignSalaryStructureRequest request, StructureTotals totals) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeId", employeeId);
        details.put("effectiveFrom", request.effectiveFrom().toString());
        details.put("grossMonthly", totals.grossMonthly().toPlainString());
        details.put("netMonthly", totals.netMonthly().toPlainString());
        details.put("annualCtc", totals.annualCtc().toPlainString());
        if (request.overrideReason() != null && !request.overrideReason().isBlank()) {
            details.put("overrideReason", request.overrideReason());
        }
        return details;
    }
}
