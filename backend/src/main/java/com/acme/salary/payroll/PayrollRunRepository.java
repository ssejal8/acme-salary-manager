package com.acme.salary.payroll;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Payroll run persistence.
 *
 * <p>Every <em>mutation</em> goes through this repository, because the run is the aggregate
 * root and the state that decides whether a payslip may change belongs to it.
 * {@link PayslipRepository} exists alongside it for <em>reads</em> only, and always fetches
 * the run with the payslip — so a payslip view can never be rendered without the status
 * that says whether it is published.
 */
public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {

    /**
     * A run with its payslips and their lines, for the review screen (FR-5.6).
     *
     * <p>The payslips are fetch-joined here; their lines are not. Both collections are
     * {@code List}s, and Hibernate refuses to fetch two bags in one query
     * ({@code MultipleBagFetchException}) — at execution, not at startup, so the app boots
     * and every caller of this method answers 500. The lines are loaded instead in
     * batches by the {@code @BatchSize} on {@link Payslip}, which keeps a thousand
     * payslips to a handful of line queries rather than a thousand.
     */
    @Query("""
            SELECT DISTINCT run FROM PayrollRun run
            LEFT JOIN FETCH run.payslips
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

}
