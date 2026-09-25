package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.salary.common.money.Money;
import com.acme.salary.common.persistence.JpaAuditingConfig;
import com.acme.salary.employee.Employee;
import com.acme.salary.employee.EmployeeRepository;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.DepartmentRepository;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.DesignationRepository;
import com.acme.salary.orgdata.Grade;
import com.acme.salary.orgdata.GradeRepository;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.support.ClockTestConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The paged payslip search, against real PostgreSQL (ADR-012).
 *
 * <p>This query cannot be covered by a unit test in any useful way. Its whole content is
 * SQL the database generates: six optional filters expressed as {@code :param IS NULL OR}
 * predicates, a subquery into another aggregate for the department filter, a fetch join
 * combined with pagination, and a separate count query that has to agree with the page.
 * A mock repository would assert only that the arguments were passed along — which
 * {@code PayslipServiceTest} already does — and would happily pass with a query that does
 * not parse.
 *
 * <p>Skipped, not failed, where Docker is unavailable. CI runs it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PayslipRepositoryIT {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the Testcontainers extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("salary_mgmt")
            .withUsername("salary_app")
            .withPassword("salary_app_test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PayslipRepository payslips;

    @Autowired
    private PayrollRunRepository runs;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private DepartmentRepository departments;

    @Autowired
    private DesignationRepository designations;

    @Autowired
    private GradeRepository grades;

    @Autowired
    private TestEntityManager entityManager;

    private Long engineerId;
    private Long analystId;
    private Long engineeringId;
    private Long financeId;
    private Long aprilRunId;
    private Long marchRunId;

    @BeforeEach
    void seedTwoRunsAcrossTwoDepartments() {
        Department engineering = departments.save(new Department("ENG", "Engineering"));
        Department finance = departments.save(new Department("FIN", "Finance"));
        Designation title = designations.save(new Designation("Software Engineer"));
        Grade grade = grades.save(
                new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000")));
        engineeringId = engineering.getId();
        financeId = finance.getId();

        Employee engineer = employees.save(new Employee(
                "IT-001", "Asha", "Menon", "asha@acme.test",
                LocalDate.of(2024, 4, 1), engineering, title, grade));
        Employee analyst = employees.save(new Employee(
                "IT-002", "Ravi", "Iyer", "ravi@acme.test",
                LocalDate.of(2024, 4, 1), finance, title, grade));
        engineerId = engineer.getId();
        analystId = analyst.getId();

        // April is finalised and holds both; March is a draft holding only the engineer.
        PayrollRun april = new PayrollRun(PayrollPeriod.of(2026, 4), 1L);
        april.computePayslips(amountsFor(engineerId, analystId));
        april.finalise(Instant.parse("2026-05-01T10:00:00Z"));
        aprilRunId = runs.save(april).getId();

        PayrollRun march = new PayrollRun(PayrollPeriod.of(2026, 3), 1L);
        march.computePayslips(amountsFor(engineerId));
        marchRunId = runs.save(march).getId();

        entityManager.flush();
        entityManager.clear();
    }

    private static Map<Long, PayslipAmounts> amountsFor(Long... employeeIds) {
        Map<Long, PayslipAmounts> amounts = new LinkedHashMap<>();
        for (Long employeeId : employeeIds) {
            amounts.put(employeeId, new PayslipAmounts(
                    30, 30, 0,
                    Money.of("50000.00"), Money.of("90000.00"),
                    Money.of("6200.00"), Money.of("83800.00"),
                    List.of(
                            new PayslipAmounts.Line("BASIC", "Basic Salary", ComponentType.EARNING,
                                    Money.of("90000.00"), 0),
                            new PayslipAmounts.Line("PF", "Provident Fund", ComponentType.DEDUCTION,
                                    Money.of("6200.00"), 1))));
        }
        return amounts;
    }

    private Page<Payslip> search(
            Long runId, Integer year, Integer month, Long departmentId, Long employeeId,
            boolean publishedOnly) {
        return payslips.search(runId, year, month, departmentId, employeeId, publishedOnly,
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("employeeId"))));
    }

    @Test
    void withNoFiltersReturnsEveryPayslip() {
        Page<Payslip> found = search(null, null, null, null, null, false);

        assertThat(found.getTotalElements()).isEqualTo(3);
    }

    @Test
    void filtersByRun() {
        assertThat(search(aprilRunId, null, null, null, null, false).getTotalElements()).isEqualTo(2);
        assertThat(search(marchRunId, null, null, null, null, false).getTotalElements()).isEqualTo(1);
    }

    @Test
    void filtersByPeriod() {
        Page<Payslip> found = search(null, 2026, 3, null, null, false);

        assertThat(found.getContent()).singleElement().satisfies(payslip -> {
            assertThat(payslip.getEmployeeId()).isEqualTo(engineerId);
            assertThat(payslip.getRun().getPeriodMonth()).isEqualTo(3);
        });
    }

    @Test
    void filtersByDepartmentThroughTheSubqueryIntoTheEmployeeAggregate() {
        // The filter a payslip cannot answer for itself: it holds an employee id, not an
        // association, so the department lives in another aggregate entirely.
        assertThat(search(null, null, null, financeId, null, false).getTotalElements()).isEqualTo(1);
        assertThat(search(null, null, null, engineeringId, null, false).getTotalElements()).isEqualTo(2);
    }

    @Test
    void filtersByEmployee() {
        assertThat(search(null, null, null, null, analystId, false).getTotalElements()).isEqualTo(1);
    }

    @Test
    void publishedOnlyExcludesDraftRuns() {
        // The restriction an employee's search is narrowed by: a draft payslip has not
        // been published and its figures may still change (FR-5.8).
        Page<Payslip> found = search(null, null, null, null, null, true);

        assertThat(found.getTotalElements()).isEqualTo(2);
        assertThat(found.getContent()).allSatisfy(payslip ->
                assertThat(payslip.getRun().isFinalised()).isTrue());
    }

    @Test
    void combinesFilters() {
        assertThat(search(aprilRunId, 2026, 4, engineeringId, engineerId, true).getTotalElements())
                .isEqualTo(1);
        // Same employee, wrong department: the filters are an AND, not an OR.
        assertThat(search(aprilRunId, 2026, 4, financeId, engineerId, true).getTotalElements())
                .isZero();
    }

    @Test
    void pagesInTheDatabaseAndCountsTheWholeResult() {
        Page<Payslip> firstPage = payslips.search(null, null, null, null, null, false,
                PageRequest.of(0, 2, Sort.by(Sort.Order.asc("id"))));

        assertThat(firstPage.getContent()).hasSize(2);
        // The count query has to agree with the page query — they are written separately,
        // which is exactly why this is asserted.
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
    }

    @Test
    void sortsByAPropertyOfTheRunAndOfThePayslip() {
        Page<Payslip> byPeriod = payslips.search(null, null, null, null, null, false,
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("run.periodMonth"))));

        assertThat(byPeriod.getContent().get(0).getRun().getPeriodMonth()).isEqualTo(3);

        Page<Payslip> byNet = payslips.search(null, null, null, null, null, false,
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("netPay"))));

        assertThat(byNet.getContent()).isNotEmpty();
    }
}
