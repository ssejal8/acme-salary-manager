package com.acme.salary.employee;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ApiError;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.employee.dto.CreateEmployeeRequest;
import com.acme.salary.employee.dto.DeactivateEmployeeRequest;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import com.acme.salary.employee.dto.UpdateEmployeeRequest;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.DepartmentRepository;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.DesignationRepository;
import com.acme.salary.orgdata.Grade;
import com.acme.salary.orgdata.GradeRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee master data: reads, and the three writes FR-2.1, FR-2.3 and FR-2.5 describe.
 *
 * <p>The transaction boundary is here, not in the controller (architecture §4.1), and
 * entity-to-DTO mapping happens inside it so the lazily-fetched reference associations
 * resolve while the session is still open. Nothing but DTOs crosses back out (ADR-008).
 *
 * <p>There is no delete, in any form. A payslip from years ago must still resolve the
 * person it was for, so leaving is a state change with an exit date attached (ADR-014).
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final DepartmentRepository departments;
    private final DesignationRepository designations;
    private final GradeRepository grades;
    private final AuditService audit;

    public EmployeeService(
            EmployeeRepository employees,
            DepartmentRepository departments,
            DesignationRepository designations,
            GradeRepository grades,
            AuditService audit) {
        this.employees = employees;
        this.departments = departments;
        this.designations = designations;
        this.grades = grades;
        this.audit = audit;
    }

    /**
     * Creates an employee record (FR-2.1).
     *
     * <p>The uniqueness pre-checks report <em>both</em> duplicates when both are taken,
     * rather than stopping at the first. A form that has to be resubmitted twice to learn
     * two facts the server already knew is a worse form, and the cost here is one extra
     * indexed lookup.
     *
     * <p>They are also only pre-checks: the unique indexes are what actually prevent a
     * duplicate under a race, and the handler turns that into the same 409
     * (architecture §7.2).
     */
    @Transactional
    public EmployeeSummaryResponse create(CreateEmployeeRequest request) {
        // Normalised the way the entity will normalise it, because a pre-check against
        // the raw input would let "e-001" past a lookup for "E-001". The entity remains
        // the authority on the rule; this just has to ask the same question.
        String employeeCode = request.employeeCode().strip().toUpperCase();
        String workEmail = request.workEmail().strip().toLowerCase();

        List<ApiError.FieldError> duplicates = new ArrayList<>();
        if (employees.existsByEmployeeCode(employeeCode)) {
            duplicates.add(new ApiError.FieldError(
                    "employeeCode", "employee code " + employeeCode + " is already in use"));
        }
        if (employees.existsByWorkEmail(workEmail)) {
            duplicates.add(new ApiError.FieldError(
                    "workEmail", workEmail + " already belongs to another employee"));
        }
        if (!duplicates.isEmpty()) {
            throw new ConflictException("An employee with these details already exists", duplicates);
        }

        Employee employee = new Employee(
                employeeCode,
                request.firstName(),
                request.lastName(),
                workEmail,
                request.dateOfJoining(),
                department(request.departmentId()),
                designation(request.designationId()),
                grade(request.gradeId()));

        Employee saved = employees.save(employee);
        audit.record(AuditEntityType.EMPLOYEE, saved.getId(), AuditAction.EMPLOYEE_CREATED,
                Map.of(
                        "employeeCode", saved.getEmployeeCode(),
                        "dateOfJoining", saved.getDateOfJoining().toString(),
                        "grade", saved.getGrade().getName()));
        return EmployeeSummaryResponse.from(saved);
    }

    /**
     * Updates the editable fields (FR-2.3).
     *
     * <p>A leaver's record can still be corrected. Their compensation cannot be changed —
     * that rule belongs to salary structures — but a misspelled name on someone who left
     * last year is a mistake worth fixing, and refusing it would leave no way to.
     */
    @Transactional
    public EmployeeSummaryResponse update(Long id, UpdateEmployeeRequest request) {
        Employee employee = employees.findWithReferencesById(id)
                .orElseThrow(() -> NotFoundException.of("Employee", id));
        String workEmail = request.workEmail().strip().toLowerCase();

        // Excluding this record, so saving a form that did not touch the email is not a
        // conflict with itself.
        if (employees.existsByWorkEmailAndIdNot(workEmail, id)) {
            throw ConflictException.field(
                    "workEmail", workEmail + " already belongs to another employee");
        }

        employee.updateDetails(
                request.firstName(),
                request.lastName(),
                workEmail,
                department(request.departmentId()),
                designation(request.designationId()),
                grade(request.gradeId()));

        audit.record(AuditEntityType.EMPLOYEE, employee.getId(), AuditAction.EMPLOYEE_UPDATED,
                Map.of(
                        "employeeCode", employee.getEmployeeCode(),
                        "workEmail", employee.getWorkEmail(),
                        "grade", employee.getGrade().getName()));
        return EmployeeSummaryResponse.from(employee);
    }

    /**
     * Records an exit and deactivates (FR-2.5).
     *
     * <p>A second deactivation is a 409 rather than a silent overwrite: the exit date
     * decides whether the person is paid for the month they left (FR-2.6), so quietly
     * moving it is a payroll change disguised as a repeated click.
     *
     * <p>The date itself is validated by the entity, which refuses one before the joining
     * date — the same rule as the schema's {@code ck_employees_exit_after_joining}.
     */
    @Transactional
    public EmployeeSummaryResponse deactivate(Long id, DeactivateEmployeeRequest request) {
        Employee employee = employees.findWithReferencesById(id)
                .orElseThrow(() -> NotFoundException.of("Employee", id));

        if (!employee.isActive()) {
            throw new ConflictException("Employee " + employee.getEmployeeCode()
                    + " already left on " + employee.getExitDate());
        }

        employee.deactivate(request.exitDate());
        audit.record(AuditEntityType.EMPLOYEE, employee.getId(), AuditAction.EMPLOYEE_DEACTIVATED,
                Map.of(
                        "employeeCode", employee.getEmployeeCode(),
                        "exitDate", employee.getExitDate().toString()));
        return EmployeeSummaryResponse.from(employee);
    }

    /**
     * Reference lookups.
     *
     * <p>An unknown id is a 400 naming the field rather than a 404, because the employee
     * being created or updated is not what is missing — one of its references is, and the
     * form needs to know which of the three.
     */
    private Department department(Long id) {
        return departments.findById(id)
                .orElseThrow(() -> ValidationException.field("departmentId", "does not exist"));
    }

    private Designation designation(Long id) {
        return designations.findById(id)
                .orElseThrow(() -> ValidationException.field("designationId", "does not exist"));
    }

    private Grade grade(Long id) {
        return grades.findById(id)
                .orElseThrow(() -> ValidationException.field("gradeId", "does not exist"));
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
     * The employee a login belongs to (FR-2.7), which is how "my own data" is resolved.
     *
     * <p>Empty when the user has no employee record — an ADMIN or HR login provisioned
     * without one. That is not an error and must not be reported as one: the caller
     * decides what it means, and for a payslip list it means "you have none" rather than
     * "something went wrong".
     */
    @Transactional(readOnly = true)
    public Optional<EmployeeIdentity> findSelf(Long userId) {
        return employees.findWithReferencesByUserId(userId).map(EmployeeIdentity::from);
    }

    /**
     * Identity for a set of employees, keyed by id.
     *
     * <p>Batched because a payslip list needs a name per row, and per row it would be a
     * query per payslip.
     */
    @Transactional(readOnly = true)
    public Map<Long, EmployeeIdentity> identitiesOf(Collection<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, EmployeeIdentity> identities = new LinkedHashMap<>();
        for (Employee employee : employees.findAllByIdIn(employeeIds)) {
            identities.put(employee.getId(), EmployeeIdentity.from(employee));
        }
        return identities;
    }

    /** Identity for one employee, for a payslip header (FR-6.2). */
    @Transactional(readOnly = true)
    public EmployeeIdentity identityOf(Long employeeId) {
        return employees.findWithReferencesById(employeeId)
                .map(EmployeeIdentity::from)
                .orElseThrow(() -> NotFoundException.of("Employee", employeeId));
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
