package com.acme.salary.employee;

import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * Every active employee, as compensation contexts.
     *
     * <p>The cohort compensation analytics reports on (FR-7.4). Leavers are excluded
     * because the report answers "what does the organisation cost now"; whoever has left
     * costs nothing (ADR-014 makes that a filter, not a deletion).
     */
    @Transactional(readOnly = true)
    public List<EmployeeCompensationContext> activeCompensationCohort() {
        return employees.findAllByStatus(EmployeeStatus.ACTIVE).stream()
                .map(EmployeeCompensationContext::from)
                .toList();
    }

    /**
     * Everyone who belongs in a payroll run for the given period (FR-5.2, FR-2.6).
     *
     * <p>Joined on or before the period ends and not gone before it begins, which is
     * broader than "active": someone who left mid-month is still owed part of it. The date
     * predicates are pushed to the database, then {@link Employee#isEligibleForPayroll}
     * re-applies the same rule as the authority on it — one definition, and a query that
     * cannot silently diverge from it.
     *
     * <p>Whether each of them also holds an effective salary structure is the salary
     * structure feature's question, so it is not asked here.
     */
    @Transactional(readOnly = true)
    public List<EmployeeCompensationContext> payrollCohort(LocalDate periodStart, LocalDate periodEnd) {
        return employees.findPayrollEligible(periodStart, periodEnd).stream()
                .filter(employee -> employee.isEligibleForPayroll(periodStart, periodEnd))
                .map(EmployeeCompensationContext::from)
                .toList();
    }

    /**
     * The facts another feature needs before changing an employee's pay — joining date,
     * status, grade band. Published as a record so no other feature holds an
     * {@link Employee} entity (ADR-001).
     */
    @Transactional(readOnly = true)
    public EmployeeCompensationContext compensationContext(Long employeeId) {
        return employees.findWithReferencesById(employeeId)
                .map(EmployeeCompensationContext::from)
                .orElseThrow(() -> NotFoundException.of("Employee", employeeId));
    }
}
