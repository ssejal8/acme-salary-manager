package com.acme.salary.payroll;

import java.util.List;
import java.util.Optional;
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
}
