package com.acme.salary.orgdata;

import com.acme.salary.orgdata.dto.CreateDepartmentRequest;
import com.acme.salary.orgdata.dto.DepartmentResponse;
import com.acme.salary.orgdata.dto.DesignationResponse;
import com.acme.salary.orgdata.dto.GradeResponse;
import com.acme.salary.orgdata.dto.SaveDesignationRequest;
import com.acme.salary.orgdata.dto.SaveGradeRequest;
import com.acme.salary.orgdata.dto.UpdateDepartmentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisation reference data: departments, designations and grades.
 *
 * <p>Three sibling collections in one controller because they are one concern — the
 * vocabulary an employee record is expressed in — and every client that wants one
 * generally wants all three, to populate a filter bar or an employee form.
 *
 * <p>Read-only for now. Writes are ADMIN-only when they land (FR-3.3), and deletion is
 * already constrained by the database: reference data in use cannot be removed.
 *
 * <p>ADMIN and HR, matching the API surface. Not public, even though a department name is
 * hardly a secret: an unauthenticated caller would learn the shape of the organisation,
 * and nothing needs this before login.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reference data", description = "Departments, designations and grades")
public class ReferenceDataController {

    private final ReferenceDataService referenceData;

    public ReferenceDataController(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @GetMapping("/departments")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "List departments", description = "Every department, by name. Unpaged.")
    public List<DepartmentResponse> departments() {
        return referenceData.departments();
    }

    @GetMapping("/designations")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "List designations", description = "Every job title, by title. Unpaged.")
    public List<DesignationResponse> designations() {
        return referenceData.designations();
    }

    @GetMapping("/grades")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "List grades",
            description = """
                    Every pay band, by name, with its CTC bounds. Unpaged.

                    An absent bound means unbounded on that side, which never rejects a
                    package (FR-4.3).""")
    public List<GradeResponse> grades() {
        return referenceData.grades();
    }

    @PostMapping("/departments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Add a department",
            description = """
                    ADMIN only (FR-3.1). Reading these lists is open to HR, because a form
                    cannot be filled in without the options it offers; adding one changes
                    the vocabulary every employee record and every report is expressed in.

                    A duplicate code answers 409 with the field named. There is no delete
                    endpoint: employees reference these rows with ON DELETE RESTRICT, and
                    removing one would orphan the history that names it.""")
    public ResponseEntity<DepartmentResponse> createDepartment(
            @Valid @RequestBody CreateDepartmentRequest request) {
        DepartmentResponse created = referenceData.createDepartment(request);
        return ResponseEntity
                .created(URI.create("/api/v1/departments/" + created.id()))
                .body(created);
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Rename a department",
            description = """
                    The name only. The code is immutable after creation — reports and saved
                    filters refer to it — so a wrong code is fixed by adding the right
                    department rather than by redefining an existing one.""")
    public DepartmentResponse renameDepartment(
            @PathVariable Long id, @Valid @RequestBody UpdateDepartmentRequest request) {
        return referenceData.renameDepartment(id, request);
    }

    @PostMapping("/designations")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a designation", description = "ADMIN only (FR-3.2).")
    public ResponseEntity<DesignationResponse> createDesignation(
            @Valid @RequestBody SaveDesignationRequest request) {
        DesignationResponse created = referenceData.createDesignation(request);
        return ResponseEntity
                .created(URI.create("/api/v1/designations/" + created.id()))
                .body(created);
    }

    @PutMapping("/designations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Retitle a designation",
            description = """
                    Titles are unique case-insensitively, and the check excludes this row —
                    saving a form that did not change the title is not a conflict with
                    itself.""")
    public DesignationResponse retitleDesignation(
            @PathVariable Long id, @Valid @RequestBody SaveDesignationRequest request) {
        return referenceData.retitleDesignation(id, request);
    }

    @PostMapping("/grades")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Add a grade and its CTC band",
            description = """
                    ADMIN only (FR-3.3). Either bound may be omitted, and that is
                    meaningful: an absent bound is unbounded on that side, so a top grade
                    with no ceiling never rejects a package (FR-4.3).""")
    public ResponseEntity<GradeResponse> createGrade(@Valid @RequestBody SaveGradeRequest request) {
        GradeResponse created = referenceData.createGrade(request);
        return ResponseEntity
                .created(URI.create("/api/v1/grades/" + created.id()))
                .body(created);
    }

    @PutMapping("/grades/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Amend a grade's name or band",
            description = """
                    A band change applies to the **next** assignment and does not
                    re-validate the packages already assigned against it: a salary
                    structure is a record of what was agreed (ADR-009), not a claim that
                    it still fits the current band. The compensation screens show the band
                    beside the package, which is where a discrepancy becomes visible.""")
    public GradeResponse updateGrade(
            @PathVariable Long id, @Valid @RequestBody SaveGradeRequest request) {
        return referenceData.updateGrade(id, request);
    }
}
