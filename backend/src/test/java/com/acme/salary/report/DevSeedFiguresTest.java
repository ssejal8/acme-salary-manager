package com.acme.salary.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.salary.common.money.Money;
import com.acme.salary.orgdata.CtcBand;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarystructure.ComponentAmount;
import com.acme.salary.salarystructure.SalaryStructureCalculator;
import com.acme.salary.salarystructure.StructureTotals;
import com.acme.salary.support.SeedSql;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Checks the dev seed against the real calculator, with no database.
 *
 * <p>The seed file documents the figures it produces, and the README quotes them. Those
 * claims are only worth making if something checks them: this reads the seeded packages
 * straight out of the SQL, prices them with the same code the API uses, and asserts the
 * totals — so a seed edit that changes the numbers fails here rather than quietly making
 * the documentation wrong.
 *
 * <p>It also asserts the seeded data would have been accepted by the API: every package
 * has a positive basic (FR-4.2) and sits inside its grade's CTC band (FR-4.3). A demo
 * dataset that the application itself would reject is a trap for whoever reads it next.
 */
class DevSeedFiguresTest {

    private static final SeedSql REFERENCE_SEED = SeedSql.load("db/seed/V900__dev_seed.sql");
    private static final SeedSql EMPLOYEE_SEED = SeedSql.load("db/seed/V901__dev_employee_seed.sql");

    private static final Map<String, ComponentDefinition> COMPONENTS = componentDefinitions();
    private static final Map<String, CtcBand> GRADE_BANDS = gradeBands();
    private static final List<SeededEmployee> EMPLOYEES = employees();
    private static final Map<Long, Long> STRUCTURE_OWNERS = structureOwners();
    private static final Map<Long, Boolean> STRUCTURE_IS_CURRENT = structureCurrency();
    private static final Map<Long, List<ComponentAmount>> PACKAGES = packages();

    private record ComponentDefinition(String code, ComponentType type, CalculationType calculationType) {
    }

    private record SeededEmployee(long id, String code, String status, String gradeName) {
        boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    @Nested
    @DisplayName("the seed is self-consistent")
    class SelfConsistency {

        @Test
        void seedsTwelveEmployeesElevenOfThemActive() {
            assertThat(EMPLOYEES).hasSize(12);
            assertThat(EMPLOYEES.stream().filter(SeededEmployee::isActive)).hasSize(11);
        }

        @Test
        void everyStructureBelongsToASeededEmployee() {
            List<Long> employeeIds = EMPLOYEES.stream().map(SeededEmployee::id).toList();

            assertThat(STRUCTURE_OWNERS.values()).isSubsetOf(employeeIds);
        }

        @Test
        void everyStructureHasLines() {
            assertThat(PACKAGES.keySet()).containsExactlyInAnyOrderElementsOf(STRUCTURE_OWNERS.keySet());
            assertThat(PACKAGES.values()).allSatisfy(lines -> assertThat(lines).isNotEmpty());
        }

        @Test
        void everyLineReferencesAComponentTheReferenceSeedDefines() {
            // A typo in a component code would otherwise fail only at migration time.
            List<String> codes = PACKAGES.values().stream()
                    .flatMap(List::stream)
                    .map(ComponentAmount::code)
                    .distinct()
                    .toList();

            assertThat(COMPONENTS.keySet()).containsAll(codes);
        }

        @Test
        void exactlyOneEmployeeHasARaiseInTheirHistory() {
            // Employee 1001: one superseded revision plus one current, so the history
            // screen and the effective-date resolution both have something to show.
            Map<Long, Long> currentPerEmployee = new LinkedHashMap<>();
            STRUCTURE_OWNERS.forEach((structureId, employeeId) -> {
                if (STRUCTURE_IS_CURRENT.get(structureId)) {
                    currentPerEmployee.merge(employeeId, 1L, Long::sum);
                }
            });

            assertThat(STRUCTURE_OWNERS).hasSize(11);
            assertThat(currentPerEmployee).hasSize(10);           // one revision each
            assertThat(currentPerEmployee.values()).allMatch(count -> count == 1L);
        }

        @Test
        void noEmployeeHasTwoOpenRevisions() {
            // The database enforces this with a partial unique index; the seed must not be
            // the thing that discovers it.
            List<Long> owners = STRUCTURE_OWNERS.entrySet().stream()
                    .filter(entry -> STRUCTURE_IS_CURRENT.get(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();

            assertThat(owners).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("the seeded packages obey the application's own rules")
    class ApplicationRules {

        @Test
        void everyPackageIsPriceableByTheCalculator() {
            // Which also means: a positive BASIC, no duplicate component, no percentage
            // over 100, and deductions that do not exceed gross (FR-4.2).
            PACKAGES.forEach((structureId, lines) ->
                    assertThat(SalaryStructureCalculator.compute(lines).grossMonthly())
                            .describedAs("structure %d", structureId)
                            .isGreaterThan(BigDecimal.ZERO));
        }

        @Test
        void everyCurrentPackageSitsInsideItsGradeBand() {
            // FR-4.3: a package outside the band needs an override reason, and none of the
            // seeded revisions carries one.
            currentPackagesByEmployee().forEach((employee, totals) -> {
                CtcBand band = GRADE_BANDS.get(employee.gradeName());
                assertThat(band.contains(totals.annualCtc()))
                        .describedAs("%s on grade %s: annual CTC %s against band %s",
                                employee.code(), employee.gradeName(),
                                totals.annualCtc().toPlainString(), band.describe())
                        .isTrue();
            });
        }
    }

    @Nested
    @DisplayName("the figures the seed and README claim")
    class DocumentedFigures {

        @Test
        void nineActiveEmployeesHoldAPackageAndTwoDoNot() {
            CompensationMetrics metrics = activeMetrics();

            assertThat(metrics.headcount()).isEqualTo(11);
            assertThat(metrics.employeesWithPackage()).isEqualTo(9);
            assertThat(metrics.employeesWithoutPackage()).isEqualTo(2);
        }

        @Test
        void totalActiveMonthlyCostIsAsDocumented() {
            CompensationMetrics metrics = activeMetrics();

            assertThat(metrics.totalMonthlyGross()).isEqualByComparingTo("950000.00");
            assertThat(metrics.totalMonthlyDeductions()).isEqualByComparingTo("58800.00");
            assertThat(metrics.totalMonthlyNet()).isEqualByComparingTo("891200.00");
            assertThat(metrics.totalAnnualCtc()).isEqualByComparingTo("11400000.00");
        }

        @Test
        void theDistributionIsAsDocumented() {
            CompensationMetrics metrics = activeMetrics();

            assertThat(metrics.lowestMonthlyGross()).isEqualByComparingTo("50000.00");
            assertThat(metrics.medianMonthlyGross()).isEqualByComparingTo("90000.00");
            assertThat(metrics.highestMonthlyGross()).isEqualByComparingTo("250000.00");
            assertThat(metrics.averageMonthlyGross()).isEqualByComparingTo("105555.56");
        }

        @Test
        void theLeaversPackageIsExcludedFromTheCurrentCost() {
            // ADR-014: kept on record, excluded from what the organisation costs now.
            BigDecimal includingLeaver = Money.sum(currentPackagesByEmployee(false).values().stream()
                    .map(StructureTotals::grossMonthly)
                    .toList());

            assertThat(includingLeaver).isEqualByComparingTo("1040000.00");
            assertThat(activeMetrics().totalMonthlyGross()).isEqualByComparingTo("950000.00");
        }

        @Test
        void grossPerGradeIsConsistentAcrossEmployeesOnThatGrade() {
            // Two people on the same grade should not be on quietly different money in a
            // demo dataset; that reads as a mistake rather than as a decision.
            Map<String, List<BigDecimal>> grossByGrade = new LinkedHashMap<>();
            currentPackagesByEmployee().forEach((employee, totals) ->
                    grossByGrade.computeIfAbsent(employee.gradeName(), key -> new ArrayList<>())
                            .add(totals.grossMonthly()));

            assertThat(grossByGrade.get("G1")).allMatch(gross -> gross.compareTo(Money.of("50000")) == 0);
            assertThat(grossByGrade.get("G2")).allMatch(gross -> gross.compareTo(Money.of("90000")) == 0);
            assertThat(grossByGrade.get("G3")).allMatch(gross -> gross.compareTo(Money.of("150000")) == 0);
            assertThat(grossByGrade.get("G4")).allMatch(gross -> gross.compareTo(Money.of("250000")) == 0);
        }

        @Test
        void departmentTotalsAreAsDocumented() {
            // Engineering 580,000 · Finance 140,000 · Sales 140,000 · HR 90,000.
            assertThat(activeMetrics().totalMonthlyGross())
                    .isEqualByComparingTo(Money.of("580000")
                            .add(Money.of("140000"))
                            .add(Money.of("140000"))
                            .add(Money.of("90000")));
        }
    }

    // --- reading the seed ------------------------------------------------------------

    private static CompensationMetrics activeMetrics() {
        List<SeededEmployee> active = EMPLOYEES.stream().filter(SeededEmployee::isActive).toList();
        Map<SeededEmployee, StructureTotals> priced = currentPackagesByEmployee();

        List<EmployeeCompensation> compensation = active.stream()
                .filter(priced::containsKey)
                .map(employee -> EmployeeCompensation.of(
                        employee.id(), 0L, "", 0L, employee.gradeName(), priced.get(employee)))
                .toList();

        return CompensationMetrics.of(active.size(), compensation);
    }

    private static Map<SeededEmployee, StructureTotals> currentPackagesByEmployee() {
        return currentPackagesByEmployee(true);
    }

    private static Map<SeededEmployee, StructureTotals> currentPackagesByEmployee(boolean activeOnly) {
        Map<Long, SeededEmployee> byId = new LinkedHashMap<>();
        EMPLOYEES.forEach(employee -> byId.put(employee.id(), employee));

        Map<SeededEmployee, StructureTotals> priced = new LinkedHashMap<>();
        STRUCTURE_OWNERS.forEach((structureId, employeeId) -> {
            SeededEmployee employee = byId.get(employeeId);
            if (!STRUCTURE_IS_CURRENT.get(structureId) || (activeOnly && !employee.isActive())) {
                return;
            }
            priced.put(employee, SalaryStructureCalculator.compute(PACKAGES.get(structureId)));
        });
        return priced;
    }

    private static Map<String, ComponentDefinition> componentDefinitions() {
        Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
        for (List<String> row : REFERENCE_SEED.rows("salary_components", 6)) {
            definitions.put(row.get(0), new ComponentDefinition(
                    row.get(0),
                    ComponentType.valueOf(row.get(2)),
                    CalculationType.valueOf(row.get(3))));
        }
        return definitions;
    }

    private static Map<String, CtcBand> gradeBands() {
        Map<String, CtcBand> bands = new LinkedHashMap<>();
        for (List<String> row : REFERENCE_SEED.rows("grades", 3)) {
            bands.put(row.get(0), new CtcBand(new BigDecimal(row.get(1)), new BigDecimal(row.get(2))));
        }
        return bands;
    }

    private static List<SeededEmployee> employees() {
        List<SeededEmployee> employees = new ArrayList<>();
        for (List<String> row : EMPLOYEE_SEED.rows("employees", 11)) {
            employees.add(new SeededEmployee(
                    Long.parseLong(row.get(0)), row.get(1), row.get(7), row.get(10)));
        }
        return employees;
    }

    private static Map<Long, Long> structureOwners() {
        Map<Long, Long> owners = new LinkedHashMap<>();
        for (List<String> row : EMPLOYEE_SEED.rows("salary_structures", 4)) {
            owners.put(Long.parseLong(row.get(0)), Long.parseLong(row.get(1)));
        }
        return owners;
    }

    private static Map<Long, Boolean> structureCurrency() {
        Map<Long, Boolean> current = new LinkedHashMap<>();
        for (List<String> row : EMPLOYEE_SEED.rows("salary_structures", 4)) {
            current.put(Long.parseLong(row.get(0)), "NULL".equalsIgnoreCase(row.get(3)));
        }
        return current;
    }

    private static Map<Long, List<ComponentAmount>> packages() {
        Map<Long, List<ComponentAmount>> lines = new LinkedHashMap<>();
        for (List<String> row : EMPLOYEE_SEED.rows("salary_structure_components", 3)) {
            long structureId = Long.parseLong(row.get(0));
            ComponentDefinition definition = COMPONENTS.get(row.get(1));
            if (definition == null) {
                throw new IllegalStateException("seed references unknown component " + row.get(1));
            }
            lines.computeIfAbsent(structureId, key -> new ArrayList<>())
                    .add(new ComponentAmount(
                            0L,
                            definition.code(),
                            definition.code(),
                            definition.type(),
                            definition.calculationType(),
                            new BigDecimal(row.get(2))));
        }
        return lines;
    }
}
