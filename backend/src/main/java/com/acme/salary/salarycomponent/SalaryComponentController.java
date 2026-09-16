package com.acme.salary.salarycomponent;

import com.acme.salary.salarycomponent.dto.CreateSalaryComponentRequest;
import com.acme.salary.salarycomponent.dto.SalaryComponentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salary component definitions.
 *
 * <p>HR reads them — the list populates the structure assignment form — but only ADMIN
 * defines them (FR-3.4): a component is reference data that changes what payroll computes
 * for everyone.
 */
@RestController
@RequestMapping("/api/v1/salary-components")
@Tag(name = "Salary components", description = "Definitions of earnings and deductions")
public class SalaryComponentController {

    private final SalaryComponentService components;

    public SalaryComponentController(SalaryComponentService components) {
        this.components = components;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "List salary component definitions")
    public List<SalaryComponentResponse> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return components.list(includeInactive);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Get one salary component definition")
    public SalaryComponentResponse get(@PathVariable Long id) {
        return components.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Define a salary component")
    public ResponseEntity<SalaryComponentResponse> create(
            @Valid @RequestBody CreateSalaryComponentRequest request) {
        SalaryComponentResponse created = components.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/salary-components/" + created.id()))
                .body(created);
    }
}
