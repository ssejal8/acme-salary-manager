package com.acme.salary.orgdata;

import com.acme.salary.orgdata.dto.DepartmentResponse;
import com.acme.salary.orgdata.dto.DesignationResponse;
import com.acme.salary.orgdata.dto.GradeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
}
