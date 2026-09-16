package com.acme.salary.salarystructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Salary structure persistence.
 *
 * <p>Reads fetch the components and their definitions in the same query: totals cannot be
 * computed without them, so leaving them lazy would guarantee an N+1 on every history
 * screen.
 */
public interface SalaryStructureRepository extends JpaRepository<SalaryStructure, Long> {

    /** Full revision history, newest first (FR-4.4). */
    @Query("""
            SELECT DISTINCT s FROM SalaryStructure s
            LEFT JOIN FETCH s.components line
            LEFT JOIN FETCH line.component
            WHERE s.employeeId = :employeeId
            ORDER BY s.effectiveFrom DESC
            """)
    List<SalaryStructure> findHistory(@Param("employeeId") Long employeeId);

    /** The open revision, if any. At most one exists — a partial unique index enforces it. */
    @Query("""
            SELECT DISTINCT s FROM SalaryStructure s
            LEFT JOIN FETCH s.components line
            LEFT JOIN FETCH line.component
            WHERE s.employeeId = :employeeId AND s.supersededOn IS NULL
            """)
    Optional<SalaryStructure> findCurrent(@Param("employeeId") Long employeeId);

    /**
     * The revision governing pay on a given date — what a payroll run resolves per
     * employee (FR-5.3). Effective on or before the date, and not superseded until after
     * it.
     */
    @Query("""
            SELECT DISTINCT s FROM SalaryStructure s
            LEFT JOIN FETCH s.components line
            LEFT JOIN FETCH line.component
            WHERE s.employeeId = :employeeId
              AND s.effectiveFrom <= :onDate
              AND (s.supersededOn IS NULL OR s.supersededOn > :onDate)
            """)
    Optional<SalaryStructure> findEffectiveOn(
            @Param("employeeId") Long employeeId, @Param("onDate") LocalDate onDate);

    /**
     * The open revision for each of many employees, in one query rather than one per
     * employee — the batch-read habit compensation analytics and payroll both depend on.
     */
    @Query("""
            SELECT DISTINCT s FROM SalaryStructure s
            LEFT JOIN FETCH s.components line
            LEFT JOIN FETCH line.component
            WHERE s.employeeId IN :employeeIds AND s.supersededOn IS NULL
            """)
    List<SalaryStructure> findCurrentForEmployees(@Param("employeeIds") Collection<Long> employeeIds);

    boolean existsByEmployeeIdAndEffectiveFrom(Long employeeId, LocalDate effectiveFrom);

    boolean existsByEmployeeId(Long employeeId);
}
