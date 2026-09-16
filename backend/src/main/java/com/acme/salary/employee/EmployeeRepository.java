package com.acme.salary.employee;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Employee persistence.
 *
 * <p>Two habits this interface is shaped to encourage:
 *
 * <ul>
 *   <li><b>Status is never forgotten.</b> Soft deletion means an unqualified query
 *       includes leavers (ADR-014), so the list query takes an {@link EmployeeSearch}
 *       whose status filter is mandatory, and the payroll query carries its own date
 *       predicates.
 *   <li><b>Lists do not N+1.</b> The paged read fetches department, designation and grade
 *       in the same query; a list of 100 employees must not become 301 queries
 *       (architecture §5.3 makes the same point for payroll).
 * </ul>
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    /**
     * Paged, filtered list for HR screens (FR-2.4). Paging and filtering both happen in
     * the database (NFR-1.2).
     */
    @Override
    @EntityGraph(attributePaths = {"department", "designation", "grade"})
    Page<Employee> findAll(Specification<Employee> specification, Pageable pageable);

    @EntityGraph(attributePaths = {"department", "designation", "grade"})
    Optional<Employee> findWithReferencesById(Long id);

    Optional<Employee> findByEmployeeCode(String employeeCode);

    Optional<Employee> findByWorkEmail(String workEmail);

    /** The login provisioned for an employee (FR-2.7), used to resolve "my own data". */
    Optional<Employee> findByUserId(Long userId);

    /**
     * Uniqueness pre-checks, so a duplicate produces a field-level 409 rather than a
     * database error (FR-2.2). The unique indexes remain the real guard under a race.
     */
    boolean existsByEmployeeCode(String employeeCode);

    boolean existsByWorkEmail(String workEmail);

    boolean existsByWorkEmailAndIdNot(String workEmail, Long id);

    long countByStatus(EmployeeStatus status);

    /**
     * Every employee with the given status, reference data included.
     *
     * <p>Unpaged on purpose: compensation analytics reports on the whole organisation, so
     * a page would give a wrong total. The entity graph keeps it to one query, and the
     * ceiling is the same few thousand employees a payroll run already loads
     * (architecture §5.3).
     */
    @EntityGraph(attributePaths = {"department", "designation", "grade"})
    List<Employee> findAllByStatus(EmployeeStatus status);

    /**
     * Everyone who belongs in a payroll run for the given period (FR-2.6, FR-5.2).
     *
     * <p>Joined on or before the period ends, and not gone before it begins. There is no
     * status predicate on purpose: the schema's {@code ck_employees_inactive_has_exit_date}
     * guarantees an INACTIVE employee always has an exit date, so the date test subsumes
     * the status test — and unlike a status test, it also handles someone who leaves
     * mid-period and is still owed part of the month.
     */
    @Query("""
            SELECT e FROM Employee e
            WHERE e.dateOfJoining <= :periodEnd
              AND (e.exitDate IS NULL OR e.exitDate >= :periodStart)
            ORDER BY e.employeeCode
            """)
    List<Employee> findPayrollEligible(
            @Param("periodStart") LocalDate periodStart, @Param("periodEnd") LocalDate periodEnd);

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.department.id = :departmentId AND e.status = 'ACTIVE'")
    long countActiveByDepartment(@Param("departmentId") Long departmentId);
}
