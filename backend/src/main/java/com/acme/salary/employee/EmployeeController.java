package com.acme.salary.employee;

import com.acme.salary.common.web.PageResponse;
import com.acme.salary.common.web.PageableSanitizer;
import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee read endpoints.
 *
 * <p>HTTP concerns only: bind parameters, sanitise the page request, delegate. No business
 * logic and no transaction (architecture §4.1).
 *
 * <p>Both endpoints are ADMIN/HR. An employee reaching their own record is a different
 * endpoint with an ownership check, not a relaxation of this one (FR-1.5) — and until the
 * JWT filter lands, an unauthenticated caller gets 401 from the deny-by-default chain.
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
