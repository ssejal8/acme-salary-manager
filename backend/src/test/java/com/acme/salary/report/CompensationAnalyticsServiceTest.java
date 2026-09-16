package com.acme.salary.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.money.Money;
import com.acme.salary.employee.EmployeeCompensationContext;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.employee.EmployeeStatus;
import com.acme.salary.orgdata.CtcBand;
import com.acme.salary.report.dto.CompensationGroupResponse;
import com.acme.salary.report.dto.CompensationOverviewResponse;
import com.acme.salary.salarystructure.SalaryStructureService;
import com.acme.salary.salarystructure.StructureTotals;
import com.acme.salary.support.ClockTestConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Aggregation behaviour with both feature services mocked: grouping, coverage gaps, and
 * the shares. No database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompensationAnalyticsServiceTest {

    @Mock
    private EmployeeService employees;

    @Mock
    private SalaryStructureService structures;

    private CompensationAnalyticsService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ClockTestConfig.FIXED_INSTANT, ZoneOffset.UTC);
        service = new CompensationAnalyticsService(employees, structures, clock);
    }

    private static EmployeeCompensationContext employee(
            long id, long departmentId, String departmentName, long gradeId, String gradeName) {
        return new EmployeeCompensationContext(
                id, "E-00" + id, "Employee " + id, LocalDate.of(2024, 4, 1), null,
                EmployeeStatus.ACTIVE, departmentId, departmentName, gradeId, gradeName,
                CtcBand.UNBOUNDED);
    }

    /** Totals for a package whose only earning is the given gross. */
    private static StructureTotals totals(String gross, String deductions) {
        BigDecimal grossAmount = Money.of(gross);
        BigDecimal deductionAmount = Money.of(deductions);
        return new StructureTotals(
                grossAmount,
                grossAmount,
                deductionAmount,
                Money.normalize(grossAmount.subtract(deductionAmount)),
                Money.normalize(grossAmount.multiply(BigDecimal.valueOf(12))),
                List.of(),
                List.of());
    }

    private void givenCohort(List<EmployeeCompensationContext> cohort, Map<Long, StructureTotals> priced) {
        when(employees.activeCompensationCohort()).thenReturn(cohort);
        when(structures.currentTotalsFor(any())).thenReturn(priced);
    }

    @Nested
    @DisplayName("organisation figures")
    class Organisation {

        @Test
        void totalCurrentCostIsTheSumOfEveryPackage() {
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "6000"));
            priced.put(2L, totals("50000", "4000"));
            givenCohort(List.of(
                    employee(1L, 10L, "Engineering", 30L, "G2"),
                    employee(2L, 10L, "Engineering", 30L, "G2")), priced);

            CompensationOverviewResponse overview = service.overview();

            assertThat(overview.organisation().totalMonthlyGross()).isEqualByComparingTo("120000.00");
            assertThat(overview.organisation().totalMonthlyDeductions()).isEqualByComparingTo("10000.00");
            assertThat(overview.organisation().totalMonthlyNet()).isEqualByComparingTo("110000.00");
            assertThat(overview.organisation().totalAnnualCtc()).isEqualByComparingTo("1440000.00");
        }

        @Test
        void stampsTheReportFromTheApplicationClock() {
            givenCohort(List.of(), Map.of());

            assertThat(service.overview().generatedAt()).isEqualTo(ClockTestConfig.FIXED_INSTANT);
        }

        @Test
        void anOrganisationWithNoEmployeesReportsZerosRatherThanFailing() {
            givenCohort(List.of(), Map.of());

            CompensationOverviewResponse overview = service.overview();

            assertThat(overview.organisation().headcount()).isZero();
            assertThat(overview.organisation().totalMonthlyGross()).isEqualByComparingTo("0.00");
            assertThat(overview.byDepartment()).isEmpty();
            assertThat(overview.byGrade()).isEmpty();
        }

        @Test
        void doesNotQueryForPackagesWhenThereIsNobodyToPrice() {
            givenCohort(List.of(), Map.of());

            service.overview();

            verify(structures, never()).currentTotalsFor(any());
        }

        @Test
        void pricesTheWholeCohortInOneBatchRatherThanPerEmployee() {
            // The N+1 this report would otherwise be: one query per employee.
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "0"));
            priced.put(2L, totals("50000", "0"));
            priced.put(3L, totals("30000", "0"));
            givenCohort(List.of(
                    employee(1L, 10L, "Engineering", 30L, "G2"),
                    employee(2L, 10L, "Engineering", 30L, "G2"),
                    employee(3L, 20L, "Finance", 30L, "G2")), priced);
            ArgumentCaptor<Collection<Long>> requestedIds = ArgumentCaptor.captor();

            service.overview();

            verify(structures, times(1)).currentTotalsFor(requestedIds.capture());
            assertThat(requestedIds.getValue()).containsExactly(1L, 2L, 3L);
        }
    }

    @Nested
    @DisplayName("coverage")
    class Coverage {

        @Test
        void countsEmployeesWithNoPackageWithoutPricingThemAtZero() {
            // Two of three priced: the third is a gap, and must not drag the average down.
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "0"));
            priced.put(2L, totals("50000", "0"));
            givenCohort(List.of(
                    employee(1L, 10L, "Engineering", 30L, "G2"),
                    employee(2L, 10L, "Engineering", 30L, "G2"),
                    employee(3L, 10L, "Engineering", 30L, "G2")), priced);

            var organisation = service.overview().organisation();

            assertThat(organisation.headcount()).isEqualTo(3);
            assertThat(organisation.employeesWithPackage()).isEqualTo(2);
            assertThat(organisation.employeesWithoutPackage()).isEqualTo(1);
            assertThat(organisation.averageMonthlyGross()).isEqualByComparingTo("60000.00");
        }

        @Test
        void aDepartmentWhereNobodyIsPricedStillAppearsWithItsHeadcount() {
            // Vanishing from the report would hide the gap rather than surface it.
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "0"));
            givenCohort(List.of(
                    employee(1L, 10L, "Engineering", 30L, "G2"),
                    employee(2L, 20L, "Finance", 30L, "G2")), priced);

            List<CompensationGroupResponse> byDepartment = service.overview().byDepartment();

            assertThat(byDepartment).extracting(CompensationGroupResponse::groupName)
                    .containsExactly("Engineering", "Finance");
            CompensationGroupResponse finance = byDepartment.get(1);
            assertThat(finance.metrics().headcount()).isEqualTo(1);
            assertThat(finance.metrics().employeesWithoutPackage()).isEqualTo(1);
            assertThat(finance.metrics().totalMonthlyGross()).isEqualByComparingTo("0.00");
            assertThat(finance.shareOfMonthlyGross()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("breakdowns")
    class Breakdowns {

        private void givenTwoDepartmentsAndTwoGrades() {
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "6000"));
            priced.put(2L, totals("50000", "4000"));
            priced.put(3L, totals("30000", "2000"));
            givenCohort(List.of(
                    employee(1L, 10L, "Engineering", 31L, "G3"),
                    employee(2L, 10L, "Engineering", 30L, "G2"),
                    employee(3L, 20L, "Finance", 30L, "G2")), priced);
        }

        @Test
        void groupByDepartmentSumsAndSharesCorrectly() {
            givenTwoDepartmentsAndTwoGrades();

            List<CompensationGroupResponse> byDepartment = service.overview().byDepartment();

            assertThat(byDepartment).hasSize(2);
            CompensationGroupResponse engineering = byDepartment.get(0);
            assertThat(engineering.groupId()).isEqualTo(10L);
            assertThat(engineering.metrics().headcount()).isEqualTo(2);
            assertThat(engineering.metrics().totalMonthlyGross()).isEqualByComparingTo("120000.00");
            assertThat(engineering.shareOfMonthlyGross()).isEqualByComparingTo("80.00");
            assertThat(byDepartment.get(1).shareOfMonthlyGross()).isEqualByComparingTo("20.00");
        }

        @Test
        void groupByGradeUsesTheSameFiguresAlongTheOtherDimension() {
            givenTwoDepartmentsAndTwoGrades();

            List<CompensationGroupResponse> byGrade = service.overview().byGrade();

            assertThat(byGrade).extracting(CompensationGroupResponse::groupName)
                    .containsExactly("G2", "G3");
            assertThat(byGrade.get(0).metrics().totalMonthlyGross()).isEqualByComparingTo("80000.00");
            assertThat(byGrade.get(1).metrics().totalMonthlyGross()).isEqualByComparingTo("70000.00");
        }

        @Test
        void sortsTheMostExpensiveGroupFirst() {
            // The salary bill is reviewed top-down; the biggest number is the one that
            // matters.
            givenTwoDepartmentsAndTwoGrades();

            assertThat(service.overview().byDepartment())
                    .extracting(group -> group.metrics().totalMonthlyGross())
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        }

        @Test
        void sharesAcrossGroupsAddUpToOneHundred() {
            givenTwoDepartmentsAndTwoGrades();

            BigDecimal total = service.overview().byDepartment().stream()
                    .map(CompensationGroupResponse::shareOfMonthlyGross)
                    .reduce(Money.ZERO, BigDecimal::add);

            assertThat(total).isEqualByComparingTo("100.00");
        }

        @Test
        void eachGroupCarriesItsOwnDistribution() {
            givenTwoDepartmentsAndTwoGrades();

            CompensationGroupResponse engineering = service.overview().byDepartment().get(0);

            assertThat(engineering.metrics().lowestMonthlyGross()).isEqualByComparingTo("50000.00");
            assertThat(engineering.metrics().highestMonthlyGross()).isEqualByComparingTo("70000.00");
            assertThat(engineering.metrics().medianMonthlyGross()).isEqualByComparingTo("60000.00");
        }
    }

    @Nested
    @DisplayName("single-dimension endpoints")
    class SingleDimension {

        @Test
        void byDepartmentDoesNotComputeTheGradeBreakdown() {
            // Both dimensions cost a full pass over the cohort; asking for one should not
            // pay for two.
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "0"));
            givenCohort(List.of(employee(1L, 10L, "Engineering", 30L, "G2")), priced);

            List<CompensationGroupResponse> byDepartment = service.byDepartment();

            assertThat(byDepartment).extracting(CompensationGroupResponse::groupName)
                    .containsExactly("Engineering");
            verify(employees, times(1)).activeCompensationCohort();
            verify(structures, times(1)).currentTotalsFor(any());
        }

        @Test
        void summaryReportsOrganisationFiguresOnly() {
            Map<Long, StructureTotals> priced = new LinkedHashMap<>();
            priced.put(1L, totals("70000", "6000"));
            givenCohort(List.of(employee(1L, 10L, "Engineering", 30L, "G2")), priced);

            assertThat(service.organisationMetrics().totalMonthlyGross()).isEqualByComparingTo("70000.00");
        }
    }
}
