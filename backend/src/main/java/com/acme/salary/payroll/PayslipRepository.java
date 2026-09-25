package com.acme.salary.payroll;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Payslip reads.
 *
 * <p>A read-only companion to {@link PayrollRunRepository}: every mutation still goes
 * through the run, which is the aggregate root. This exists because the payslip views
 * address a payslip directly, and reaching one through its run would mean loading the
 * whole run — a thousand other people's salaries — to render one payslip.
 *
 * <p>Both queries fetch the run, never optionally. The run carries the period a payslip is
 * for and the status that decides whether it is published at all, so a payslip without its
 * run is not something this application should be able to hold.
 *
 * <p>The fetch join on {@code lines} is safe to combine with these filters because the
 * filters are on the payslip and its run, never on the fetched collection itself.
 * Filtering a fetch-joined collection would silently return a partially loaded one.
 */
public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    /**
     * An employee's published payslips, newest period first (FR-6.1).
     *
     * <p>Restricted to FINALISED runs in the query rather than filtered afterwards: a
     * draft payslip is not published and an employee must never see one (FR-5.8).
     * Enforcing that here means no caller can forget it.
     */
    @Query("""
            SELECT DISTINCT payslip FROM Payslip payslip
            JOIN FETCH payslip.run run
            LEFT JOIN FETCH payslip.lines
            WHERE payslip.employeeId = :employeeId
              AND run.status = com.acme.salary.payroll.PayrollRunStatus.FINALISED
            ORDER BY run.periodYear DESC, run.periodMonth DESC
            """)
    List<Payslip> findPublishedForEmployee(@Param("employeeId") Long employeeId);

    /**
     * One payslip with its run and lines, whatever its run's status.
     *
     * <p>Deliberately not restricted to FINALISED: HR reviews draft payslips, so the status
     * is returned for the service to judge rather than used to hide the row. Who may see a
     * draft is an authorisation decision, not a query one.
     */
    @Query("""
            SELECT payslip FROM Payslip payslip
            JOIN FETCH payslip.run
            LEFT JOIN FETCH payslip.lines
            WHERE payslip.id = :id
            """)
    Optional<Payslip> findWithRunAndLines(@Param("id") Long id);

    /**
     * Paged payslip search (FR-6.5, FR-7.1), and what the run review screen reads.
     *
     * <p>Every filter is optional — a null parameter means "do not filter on this" — so
     * one query serves the register for a period, an HR search across periods, and the
     * payslips of a single run. Filtering, sorting and counting all happen in the database
     * (NFR-1.2); at ten thousand employees a run's payslips cannot be a list in memory.
     *
     * <p>The run is fetch-joined because every row needs its period and status, and a
     * {@code ManyToOne} fetch is safe to combine with pagination. The {@code lines}
     * collection deliberately is not: fetching a collection alongside {@code
     * firstResult}/{@code maxResults} makes Hibernate page in memory, which is exactly
     * what this method exists to avoid.
     *
     * <p>The department filter goes through a subquery on {@code Employee} rather than a
     * join, because a payslip holds its employee as an id and not an association — the
     * seam ADR-001 keeps open. The name shown in each row is resolved by the service
     * through the employee feature's published identity port, in one batch per page.
     */
    @Query(value = """
            SELECT payslip FROM Payslip payslip
            JOIN FETCH payslip.run run
            WHERE (:runId IS NULL OR run.id = :runId)
              AND (:periodYear IS NULL OR run.periodYear = :periodYear)
              AND (:periodMonth IS NULL OR run.periodMonth = :periodMonth)
              AND (:employeeId IS NULL OR payslip.employeeId = :employeeId)
              AND (:publishedOnly = FALSE
                   OR run.status = com.acme.salary.payroll.PayrollRunStatus.FINALISED)
              AND (:departmentId IS NULL OR payslip.employeeId IN (
                    SELECT employee.id FROM Employee employee
                    WHERE employee.department.id = :departmentId))
            """,
            countQuery = """
            SELECT count(payslip) FROM Payslip payslip
            JOIN payslip.run run
            WHERE (:runId IS NULL OR run.id = :runId)
              AND (:periodYear IS NULL OR run.periodYear = :periodYear)
              AND (:periodMonth IS NULL OR run.periodMonth = :periodMonth)
              AND (:employeeId IS NULL OR payslip.employeeId = :employeeId)
              AND (:publishedOnly = FALSE
                   OR run.status = com.acme.salary.payroll.PayrollRunStatus.FINALISED)
              AND (:departmentId IS NULL OR payslip.employeeId IN (
                    SELECT employee.id FROM Employee employee
                    WHERE employee.department.id = :departmentId))
            """)
    Page<Payslip> search(
            @Param("runId") Long runId,
            @Param("periodYear") Integer periodYear,
            @Param("periodMonth") Integer periodMonth,
            @Param("departmentId") Long departmentId,
            @Param("employeeId") Long employeeId,
            @Param("publishedOnly") boolean publishedOnly,
            Pageable pageable);
}
