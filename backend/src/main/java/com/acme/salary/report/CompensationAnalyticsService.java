package com.acme.salary.report;

import com.acme.salary.employee.EmployeeCompensationContext;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.report.dto.CompensationGroupResponse;
import com.acme.salary.report.dto.CompensationMetricsResponse;
import com.acme.salary.report.dto.CompensationOverviewResponse;
import com.acme.salary.salarystructure.SalaryStructureService;
import com.acme.salary.salarystructure.StructureTotals;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compensation analytics: what the organisation's current packages cost, in total and by
 * department and grade (FR-7.2, FR-7.4).
 *
 * <p>Composed from two feature services rather than a joined query, so this package holds
 * no other feature's entities (ADR-001): the employee feature supplies the active cohort,
 * the salary structure feature prices it. That costs three queries in total — the cohort,
 * the cohort's current revisions, and their components — and buys a single implementation
 * of the compensation arithmetic. A cost report and a payslip cannot disagree about what
 * a package is worth, because the same calculator produces both.
 *
 * <p>The aggregation itself happens in memory. Doing it in SQL would mean a second copy
 * of the rounding and percent-of-basic rules living in a query, and ADR-006 keeps those in
 * exactly one place. The trade is that this loads the whole active cohort: fine at the few
 * thousand employees this system targets, and the point at which it stops being fine is
 * the same point at which materialised aggregates become worthwhile
 * (architecture §12).
 */
@Service
public class CompensationAnalyticsService {

    private final EmployeeService employees;
    private final SalaryStructureService structures;
    private final Clock clock;

    public CompensationAnalyticsService(
            EmployeeService employees, SalaryStructureService structures, Clock clock) {
        this.employees = employees;
        this.structures = structures;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CompensationOverviewResponse overview() {
        PricedCohort cohort = load();

        return new CompensationOverviewResponse(
                clock.instant(),
                CompensationMetricsResponse.from(cohort.organisation()),
                cohort.groupBy(Dimension.DEPARTMENT),
                cohort.groupBy(Dimension.GRADE));
    }

    /** Organisation-wide figures only, for a dashboard tile. */
    @Transactional(readOnly = true)
    public CompensationMetricsResponse organisationMetrics() {
        return CompensationMetricsResponse.from(load().organisation());
    }

    @Transactional(readOnly = true)
    public List<CompensationGroupResponse> byDepartment() {
        // Loads and prices the cohort once and groups only what was asked for — calling
        // overview() here would compute the grade breakdown nobody requested.
        return load().groupBy(Dimension.DEPARTMENT);
    }

    @Transactional(readOnly = true)
    public List<CompensationGroupResponse> byGrade() {
        return load().groupBy(Dimension.GRADE);
    }

    private PricedCohort load() {
        List<EmployeeCompensationContext> cohort = employees.activeCompensationCohort();
        List<EmployeeCompensation> priced = price(cohort);
        return new PricedCohort(cohort, priced, CompensationMetrics.of(cohort.size(), priced));
    }

    /** The dimensions a compensation cost can be broken down along. */
    private enum Dimension {
        DEPARTMENT(
                EmployeeCompensationContext::departmentId,
                EmployeeCompensationContext::departmentName,
                EmployeeCompensation::departmentId),
        GRADE(
                EmployeeCompensationContext::gradeId,
                EmployeeCompensationContext::gradeName,
                EmployeeCompensation::gradeId);

        private final Function<EmployeeCompensationContext, Long> key;
        private final Function<EmployeeCompensationContext, String> label;
        private final Function<EmployeeCompensation, Long> pricedKey;

        Dimension(
                Function<EmployeeCompensationContext, Long> key,
                Function<EmployeeCompensationContext, String> label,
                Function<EmployeeCompensation, Long> pricedKey) {
            this.key = key;
            this.label = label;
            this.pricedKey = pricedKey;
        }
    }

    /** The cohort, its prices and its organisation-wide totals, computed once per request. */
    private record PricedCohort(
            List<EmployeeCompensationContext> contexts,
            List<EmployeeCompensation> priced,
            CompensationMetrics organisation) {

        List<CompensationGroupResponse> groupBy(Dimension dimension) {
            return group(contexts, priced, organisation.totalMonthlyGross(),
                    dimension.key, dimension.label, dimension.pricedKey);
        }
    }

    /**
     * Prices the cohort. Employees with no current package are simply absent from the
     * result — they are counted as a gap by {@link CompensationMetrics}, not priced at
     * zero.
     */
    private List<EmployeeCompensation> price(List<EmployeeCompensationContext> cohort) {
        if (cohort.isEmpty()) {
            return List.of();
        }
        Map<Long, StructureTotals> totals = structures.currentTotalsFor(
                cohort.stream().map(EmployeeCompensationContext::employeeId).toList());

        List<EmployeeCompensation> priced = new ArrayList<>();
        for (EmployeeCompensationContext employee : cohort) {
            StructureTotals employeeTotals = totals.get(employee.employeeId());
            if (employeeTotals != null) {
                priced.add(EmployeeCompensation.of(
                        employee.employeeId(),
                        employee.departmentId(),
                        employee.departmentName(),
                        employee.gradeId(),
                        employee.gradeName(),
                        employeeTotals));
            }
        }
        return priced;
    }

    /**
     * Groups the cohort along one dimension, sorted by cost descending — the order someone
     * reviewing a salary bill wants, since the largest group is the one worth looking at.
     *
     * <p>Headcount per group comes from the cohort, not from the priced list, so a
     * department where nobody has a package yet still appears with a zero cost and a
     * visible gap instead of vanishing from the report.
     */
    private static List<CompensationGroupResponse> group(
            List<EmployeeCompensationContext> cohort,
            List<EmployeeCompensation> priced,
            BigDecimal organisationGross,
            Function<EmployeeCompensationContext, Long> cohortKey,
            Function<EmployeeCompensationContext, String> cohortLabel,
            Function<EmployeeCompensation, Long> pricedKey) {

        Map<Long, String> labels = new LinkedHashMap<>();
        Map<Long, Integer> headcounts = new LinkedHashMap<>();
        for (EmployeeCompensationContext employee : cohort) {
            Long key = cohortKey.apply(employee);
            labels.putIfAbsent(key, cohortLabel.apply(employee));
            headcounts.merge(key, 1, Integer::sum);
        }

        Map<Long, List<EmployeeCompensation>> pricedByKey = new LinkedHashMap<>();
        for (EmployeeCompensation employee : priced) {
            pricedByKey.computeIfAbsent(pricedKey.apply(employee), key -> new ArrayList<>()).add(employee);
        }

        return headcounts.entrySet().stream()
                .map(entry -> {
                    Long key = entry.getKey();
                    CompensationMetrics metrics = CompensationMetrics.of(
                            entry.getValue(), pricedByKey.getOrDefault(key, List.of()));
                    return CompensationGroupResponse.from(
                            key, labels.get(key), metrics, organisationGross);
                })
                .sorted(Comparator
                        .comparing((CompensationGroupResponse group) ->
                                group.metrics().totalMonthlyGross())
                        .reversed()
                        .thenComparing(CompensationGroupResponse::groupName))
                .toList();
    }
}
