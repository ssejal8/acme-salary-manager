package com.acme.salary.salarycomponent;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.salarycomponent.dto.CreateSalaryComponentRequest;
import com.acme.salary.salarycomponent.dto.SalaryComponentResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Salary component definitions: the vocabulary every salary structure is built from. */
@Service
public class SalaryComponentService {

    private final SalaryComponentRepository components;
    private final AuditService audit;

    public SalaryComponentService(SalaryComponentRepository components, AuditService audit) {
        this.components = components;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SalaryComponentResponse> list(boolean includeInactive) {
        List<SalaryComponent> found = includeInactive
                ? components.findAllByOrderByTypeAscCodeAsc()
                : components.findAllByActiveTrueOrderByTypeAscCodeAsc();
        return found.stream().map(SalaryComponentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SalaryComponentResponse findById(Long id) {
        return components.findById(id)
                .map(SalaryComponentResponse::from)
                .orElseThrow(() -> NotFoundException.of("Salary component", id));
    }

    @Transactional
    public SalaryComponentResponse create(CreateSalaryComponentRequest request) {
        SalaryComponent component = new SalaryComponent(
                request.code(),
                request.name(),
                request.type(),
                request.calculationType(),
                request.value(),
                request.taxable());

        // Pre-checked for a clean 409 with a readable message; the unique index is what
        // actually prevents the duplicate under a race (architecture §7.2).
        if (components.existsByCode(component.getCode())) {
            throw new ConflictException(
                    "A salary component with code " + component.getCode() + " already exists");
        }

        SalaryComponent saved = components.save(component);
        audit.record(AuditEntityType.SALARY_COMPONENT, saved.getId(), AuditAction.SALARY_COMPONENT_CREATED,
                Map.of("code", saved.getCode(), "type", saved.getType(),
                        "calculationType", saved.getCalculationType()));
        return SalaryComponentResponse.from(saved);
    }
}
