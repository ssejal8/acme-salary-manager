package com.acme.salary.employee;

import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee reads.
 *
 * <p>The transaction boundary is here, not in the controller (architecture §4.1), and
 * entity-to-DTO mapping happens inside it so the lazily-fetched reference associations
 * resolve while the session is still open. Nothing but DTOs crosses back out (ADR-008).
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;

    public EmployeeService(EmployeeRepository employees) {
        this.employees = employees;
    }

    /**
     * Paged, filtered employee list (FR-2.4). Filtering, sorting and counting all happen
     * in the database; the {@link Pageable} is expected to have been sanitised at the web
     * layer (NFR-1.2).
     */
    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummaryResponse> search(EmployeeSearch search, Pageable pageable) {
        Page<Employee> page = employees.findAll(EmployeeSpecifications.matching(search), pageable);
        return PageResponse.of(page, EmployeeSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public EmployeeSummaryResponse findById(Long id) {
        return employees.findWithReferencesById(id)
                .map(EmployeeSummaryResponse::from)
                .orElseThrow(() -> NotFoundException.of("Employee", id));
    }
}
