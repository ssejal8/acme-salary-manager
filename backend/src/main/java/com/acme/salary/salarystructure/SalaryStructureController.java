package com.acme.salary.salarystructure;

import com.acme.salary.salarystructure.dto.AssignSalaryStructureRequest;
import com.acme.salary.salarystructure.dto.SalaryStructureResponse;
import com.acme.salary.salarystructure.dto.StructureTotalsResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * An employee's compensation packages.
 *
 * <p>Nested under the employee because a structure has no meaning apart from one.
 * ADMIN/HR only for now; the employee's own view of their package (FR-4.1 read access for
 * self) arrives with authentication, since it needs an ownership check rather than a role
 * check (FR-1.5).
 */
@RestController
@RequestMapping("/api/v1/employees/{employeeId}/salary-structures")
@Tag(name = "Salary structures", description = "Effective-dated compensation packages")
public class SalaryStructureController {

    private final SalaryStructureService structures;

    public SalaryStructureController(SalaryStructureService structures) {
        this.structures = structures;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Revision history",
            description = """
                    Every revision of this employee's package, newest first. Revisions
                    supersede rather than overwrite, so this is the full compensation
                    history and the current package is the one with no supersededOn.""")
    public List<SalaryStructureResponse> history(@PathVariable Long employeeId) {
        return structures.history(employeeId);
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "The package in force, or 204 if none has been assigned yet")
    public ResponseEntity<SalaryStructureResponse> current(@PathVariable Long employeeId) {
        return structures.current(employeeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Compute totals without saving",
            description = """
                    Validates and costs a proposed package so the assignment form can show
                    gross, deductions, net and annual CTC before anyone commits. Rejects
                    exactly what the assignment would reject.""")
    public StructureTotalsResponse preview(
            @PathVariable Long employeeId, @Valid @RequestBody AssignSalaryStructureRequest request) {
        return structures.preview(employeeId, request);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Assign a package",
            description = """
                    Creates a new revision effective from the given date and supersedes the
                    current one. Nothing is overwritten. A package outside the employee's
                    grade CTC band requires an overrideReason.""")
    public ResponseEntity<SalaryStructureResponse> assign(
            @PathVariable Long employeeId, @Valid @RequestBody AssignSalaryStructureRequest request) {
        SalaryStructureResponse assigned = structures.assign(employeeId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/employees/" + employeeId + "/salary-structures"))
                .body(assigned);
    }
}
