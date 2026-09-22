package com.acme.salary.employee;

import com.acme.salary.common.web.PageResponse;
import com.acme.salary.common.web.PageableSanitizer;
import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import com.acme.salary.employee.dto.CreateEmployeeRequest;
import com.acme.salary.employee.dto.DeactivateEmployeeRequest;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import com.acme.salary.employee.dto.UpdateEmployeeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee master data (FR-2.1 to FR-2.5).
 *
 * <p>HTTP concerns only: bind parameters, sanitise the page request, choose a status code,
 * delegate. No business logic and no transaction (architecture §4.1).
 *
 * <p>Every endpoint is ADMIN/HR. An employee reaching their own record is a different
 * endpoint with an ownership check, not a relaxation of these (FR-1.5).
 *
 * <p>There is no {@code DELETE}, and there will not be one: records are never hard-deleted
 * because a payslip from years ago must still resolve the person it was for (ADR-014).
 * Leaving is a {@code POST} to {@code /deactivate} with the exit date that payroll
 * eligibility is derived from.
 */
@RestController
@RequestMapping("/api/v1/employees")
@Tag(name = "Employees", description = "Employee master data")
public class EmployeeController {

    /**
     * Client sort key to entity property path. Sorting is restricted to this set
     * (NFR-1.2), and the indirection keeps the API contract independent of field names.
     */
    private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "employeeCode");

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "List employees",
            description = """
                    Paged, filtered employee list. Filtering, sorting and counting happen in
                    the database. Page size is capped at 100.

                    Leavers are excluded unless `status` says otherwise: soft-deleted
                    records mean an unfiltered list would quietly include them.""")
    public PageResponse<EmployeeSummaryResponse> list(
            @Parameter(description = "Case-insensitive match against the employee's full name")
            @RequestParam(required = false) String q,
            @Parameter(description = "Restrict to one department")
            @RequestParam(required = false) Long departmentId,
            @Parameter(description = "Restrict to one designation")
            @RequestParam(required = false) Long designationId,
            @Parameter(description = "Restrict to one grade")
            @RequestParam(required = false) Long gradeId,
            @Parameter(description = "Which employment statuses to include")
            @RequestParam(required = false, defaultValue = "ACTIVE_ONLY") StatusFilter status,
            @Parameter(description = "Standard page, size and sort parameters, e.g. ?page=0&size=20&sort=lastName,asc")
            @PageableDefault(size = 20) Pageable pageable) {

        EmployeeSearch search = new EmployeeSearch(q, departmentId, designationId, gradeId, status);
        Pageable sanitised = PageableSanitizer.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return employees.search(search, sanitised);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Get one employee")
    public EmployeeSummaryResponse get(@PathVariable Long id) {
        return employees.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Create an employee",
            description = """
                    Creates an ACTIVE employee record (FR-2.1).

                    Answers 409 with a field-level message if the employee code or the work
                    email is already in use (FR-2.2), and 400 naming the field if a
                    department, designation or grade id does not exist.

                    The date of joining may be in the future: a new hire who starts next
                    month is recorded now and simply not included in a payroll run until
                    their period comes round.""")
    public ResponseEntity<EmployeeSummaryResponse> create(
            @Valid @RequestBody CreateEmployeeRequest request) {
        EmployeeSummaryResponse created = employees.create(request);
        return ResponseEntity
                .created(URI.create("/api/v1/employees/" + created.id()))
                .body(created);
    }

    /**
     * A {@code PUT} of the editable set rather than a {@code PATCH}, because the client is
     * a form that loaded the record and submits all of it back. "Absent means unchanged"
     * would make a dropped field indistinguishable from a deliberate clearing.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Update an employee's editable fields",
            description = """
                    Replaces the editable fields (FR-2.3). The employee code and date of
                    joining are immutable after creation and are not part of the request:
                    the code appears on published payslips, and the joining date is what
                    every effective-dated salary structure is validated against.

                    Leaving is not an edit — it has its own endpoint.""")
    public EmployeeSummaryResponse update(
            @PathVariable Long id, @Valid @RequestBody UpdateEmployeeRequest request) {
        return employees.update(id, request);
    }

    /**
     * A {@code POST} to a named sub-resource, like finalising a payroll run. Deactivating
     * is not "setting status to INACTIVE" — it records an exit date that decides which
     * periods the person is still paid for — and a verb makes that legible where a field
     * assignment would not.
     */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Record an employee's exit",
            description = """
                    Records the exit date and sets the status to INACTIVE (FR-2.5). Records
                    are never hard-deleted: a payslip from years ago must still resolve the
                    person it was for.

                    The employee stays in payroll for a period that begins on or before
                    their exit date, and drops out afterwards (FR-2.6).

                    Answers 409 for someone who has already left, rather than quietly
                    moving their exit date — that would be a payroll change disguised as a
                    repeated click.""")
    public EmployeeSummaryResponse deactivate(
            @PathVariable Long id, @Valid @RequestBody DeactivateEmployeeRequest request) {
        return employees.deactivate(id, request);
    }

    private static Map<String, String> sortableProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("employeeCode", "employeeCode");
        properties.put("firstName", "firstName");
        properties.put("lastName", "lastName");
        properties.put("workEmail", "workEmail");
        properties.put("dateOfJoining", "dateOfJoining");
        properties.put("exitDate", "exitDate");
        properties.put("status", "status");
        // Sorting by a reference resolves to the readable label, not the foreign key.
        properties.put("department", "department.name");
        properties.put("designation", "designation.title");
        properties.put("grade", "grade.name");
        // Unmodifiable rather than Map.copyOf: insertion order is kept, so the 400 that
        // lists the allowed keys reads predictably.
        return Collections.unmodifiableMap(properties);
    }
}
