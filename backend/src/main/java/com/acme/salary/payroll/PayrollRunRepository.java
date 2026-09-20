package com.acme.salary.payroll;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Payroll run persistence.
 *
 * <p>There is no repository for {@code Payslip}: payslips are reached through their run,
 * which is the aggregate root, and are cascaded from it. A second repository would make it
 * possible to load a payslip without its run and so without the state that decides whether
 * it may be changed.
 */
public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {

    /**
     * A run with its payslips and their lines, for the review screen (FR-5.6).
     *
     * <p>Fetched in one query rather than lazily: a draft for a thousand employees would
     * otherwise be a thousand payslip queries and a thousand more for their lines.
     * {@code DISTINCT} is required because the two collection joins multiply rows.
     */
    @Query("""
            SELECT DISTINCT run FROM PayrollRun run
            LEFT JOIN FETCH run.payslips payslip
            LEFT JOIN FETCH payslip.lines
            WHERE run.id = :id
            """)
    Optional<PayrollRun> findWithPayslips(@Param("id") Long id);

    /**
     * Whether the period is already taken (FR-5.7).
     *
     * <p>A pre-check for a clean 409 with a readable message. The partial unique index
     * {@code uq_payroll_runs_active_period} is what actually prevents a double run under a
     * race — and it counts only these two statuses, so a cancelled run does not block a
     * retry.
     */
    @Query("""
            SELECT COUNT(run) > 0 FROM PayrollRun run
            WHERE run.periodYear = :year AND run.periodMonth = :month
              AND run.status IN (com.acme.salary.payroll.PayrollRunStatus.DRAFT,
                                 com.acme.salary.payroll.PayrollRunStatus.FINALISED)
            """)
    boolean existsActiveForPeriod(@Param("year") int year, @Param("month") int month);

    /**
     * A page of runs, ordered by the caller's sanitised {@link Pageable}.
     *
     * <p>No {@code ORDER BY} in the query itself: Spring Data appends the pageable's sort,
     * and the two would fight. The controller's default sort is newest period first, which
     * is what this is for.
     */
    @Query("SELECT run FROM PayrollRun run")
    Page<PayrollRun> findAllNewestFirst(Pageable pageable);

    /**
     * Every finalised payslip for an employee, newest period first (FR-6.1).
     *
     * <p>Filtered to FINALISED in the query rather than afterwards: a draft payslip is not
     * published, and an employee must not see one (FR-5.8).
     */
    @Query("""
            SELECT DISTINCT run FROM PayrollRun run
            LEFT JOIN FETCH run.payslips payslip
            LEFT JOIN FETCH payslip.lines
            WHERE payslip.employeeId = :employeeId
              AND run.status = com.acme.salary.payroll.PayrollRunStatus.FINALISED
            ORDER BY run.periodYear DESC, run.periodMonth DESC
            """)
    List<PayrollRun> findFinalisedRunsForEmployee(@Param("employeeId") Long employeeId);
}
