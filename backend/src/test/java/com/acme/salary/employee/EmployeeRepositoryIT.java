package com.acme.salary.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.persistence.JpaAuditingConfig;
import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.DepartmentRepository;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.DesignationRepository;
import com.acme.salary.orgdata.Grade;
import com.acme.salary.orgdata.GradeRepository;
import com.acme.salary.support.ClockTestConfig;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Exercises the employee mapping and queries against real PostgreSQL (ADR-012).
 *
 * <p>Booting this context is itself an assertion: {@code ddl-auto=validate} means
 * Hibernate compares every mapping against the Flyway-built schema and fails startup on a
 * mismatch. A missing column or a wrong type shows up here as a failure to start, not as a
 * surprise in production.
 *
 * <p>Skipped, not failed, where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EmployeeRepositoryIT {

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
    private EmployeeRepository employees;

    @Autowired
    private DepartmentRepository departments;

    @Autowired
    private DesignationRepository designations;

    @Autowired
    private GradeRepository grades;

    @Autowired
    private TestEntityManager entityManager;

    private Department engineering;
    private Department finance;
    private Designation engineer;
    private Grade grade;

    @BeforeEach
    void createReferenceData() {
        engineering = departments.save(new Department("ENG", "Engineering"));
        finance = departments.save(new Department("FIN", "Finance"));
        engineer = designations.save(new Designation("Software Engineer"));
        grade = grades.save(new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000")));
    }

    @Test
    void savesAndReloadsAnEmployeeWithItsAuditTimestamps() {
        Employee saved = employees.save(employee("IT-001", "Asha", "Menon", LocalDate.of(2024, 4, 1), engineering));
        entityManager.flush();
        entityManager.clear();

        Employee reloaded = employees.findWithReferencesById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEmployeeCode()).isEqualTo("IT-001");
        assertThat(reloaded.fullName()).isEqualTo("Asha Menon");
        assertThat(reloaded.getWorkEmail()).isEqualTo("it-001@acme.test");
        assertThat(reloaded.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(reloaded.getDepartment().getName()).isEqualTo("Engineering");
        assertThat(reloaded.getGrade().getMaxCtc()).isEqualByComparingTo("1500000.00");
        // Populated by JPA auditing from the application Clock, not by the column default.
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void duplicateEmployeeCodeIsRejectedByTheDatabase() {
        employees.save(employee("IT-002", "First", "Person", LocalDate.of(2024, 4, 1), engineering));
        entityManager.flush();

        Employee duplicate = new Employee("IT-002", "Second", "Person", "other@acme.test",
                LocalDate.of(2024, 5, 1), engineering, engineer, grade);
        employees.save(duplicate);

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCaseVariantEmailCannotBecomeASecondEmployee() {
        // The entity lowercases, and V2 adds the matching database CHECK, so 'A@…' and
        // 'a@…' collide on the unique index instead of creating two accounts.
        employees.save(new Employee("IT-003", "Asha", "Menon", "Asha.Menon@ACME.test",
                LocalDate.of(2024, 4, 1), engineering, engineer, grade));
        entityManager.flush();

        employees.save(new Employee("IT-004", "Other", "Person", "asha.menon@acme.test",
                LocalDate.of(2024, 4, 1), engineering, engineer, grade));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theEmployeeCodeIsNotUpdatable() {
        // FR-2.3. The mapping is what enforces this, so force a change past the absent
        // setter and confirm the UPDATE never carries the column.
        Employee saved = employees.save(employee("IT-005", "Asha", "Menon", LocalDate.of(2024, 4, 1), engineering));
        entityManager.flush();

        setFieldValue(saved, "employeeCode", "TAMPERED");
        entityManager.flush();
        entityManager.clear();

        assertThat(employees.findById(saved.getId()).orElseThrow().getEmployeeCode()).isEqualTo("IT-005");
    }

    @Test
    void deactivationPersistsBothStatusAndExitDate() {
        Employee saved = employees.save(employee("IT-006", "Exit", "Person", LocalDate.of(2024, 4, 1), engineering));
        saved.deactivate(LocalDate.of(2026, 3, 31));
        entityManager.flush();
        entityManager.clear();

        Employee reloaded = employees.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
        assertThat(reloaded.getExitDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void findPayrollEligibleAppliesTheJoiningAndExitWindow() {
        // FR-2.6 and FR-5.2, for a September 2026 run.
        LocalDate periodStart = LocalDate.of(2026, 9, 1);
        LocalDate periodEnd = LocalDate.of(2026, 9, 30);

        employees.save(employee("EL-001", "Long", "Serving", LocalDate.of(2024, 4, 1), engineering));
        employees.save(employee("EL-002", "Mid", "Joiner", LocalDate.of(2026, 9, 15), engineering));
        employees.save(employee("EL-003", "Future", "Joiner", LocalDate.of(2026, 10, 1), engineering));
        Employee earlyLeaver = employees.save(
                employee("EL-004", "Early", "Leaver", LocalDate.of(2024, 4, 1), engineering));
        earlyLeaver.deactivate(LocalDate.of(2026, 8, 31));
        Employee midLeaver = employees.save(
                employee("EL-005", "Mid", "Leaver", LocalDate.of(2024, 4, 1), engineering));
        midLeaver.deactivate(LocalDate.of(2026, 9, 20));
        entityManager.flush();
        entityManager.clear();

        List<String> eligible = employees.findPayrollEligible(periodStart, periodEnd).stream()
                .map(Employee::getEmployeeCode)
                .filter(code -> code.startsWith("EL-"))
                .toList();

        assertThat(eligible)
                .containsExactly("EL-001", "EL-002", "EL-005")
                .doesNotContain("EL-003", "EL-004");
    }

    @Test
    void theListQueryFiltersAndPagesInTheDatabase() {
        employees.save(employee("SR-001", "Asha", "Menon", LocalDate.of(2024, 4, 1), engineering));
        employees.save(employee("SR-002", "Ravi", "Menon", LocalDate.of(2024, 4, 1), finance));
        employees.save(employee("SR-003", "Asha", "Verma", LocalDate.of(2024, 4, 1), engineering));
        Employee leaver = employees.save(employee("SR-004", "Asha", "Gone", LocalDate.of(2024, 4, 1), engineering));
        leaver.deactivate(LocalDate.of(2026, 1, 31));
        entityManager.flush();
        entityManager.clear();

        Page<Employee> ashaInEngineering = employees.findAll(
                EmployeeSpecifications.matching(new EmployeeSearch(
                        "asha", engineering.getId(), null, null, StatusFilter.ACTIVE_ONLY)),
                PageRequest.of(0, 10, Sort.by("employeeCode")));

        assertThat(ashaInEngineering.getContent())
                .extracting(Employee::getEmployeeCode)
                .containsExactly("SR-001", "SR-003");
    }

    @Test
    void theDefaultStatusFilterHidesLeavers() {
        Employee leaver = employees.save(employee("ST-001", "Gone", "Person", LocalDate.of(2024, 4, 1), engineering));
        leaver.deactivate(LocalDate.of(2026, 1, 31));
        entityManager.flush();
        entityManager.clear();

        Page<Employee> active = employees.findAll(
                EmployeeSpecifications.matching(EmployeeSearch.activeEmployees()),
                PageRequest.of(0, 50, Sort.by("employeeCode")));
        Page<Employee> everyone = employees.findAll(
                EmployeeSpecifications.matching(
                        EmployeeSearch.activeEmployees().withStatusFilter(StatusFilter.ALL)),
                PageRequest.of(0, 50, Sort.by("employeeCode")));

        assertThat(active.getContent()).extracting(Employee::getEmployeeCode).doesNotContain("ST-001");
        assertThat(everyone.getContent()).extracting(Employee::getEmployeeCode).contains("ST-001");
    }

    @Test
    void paginationLimitsTheResultSetRatherThanTheList() {
        for (int i = 1; i <= 5; i++) {
            employees.save(employee("PG-00" + i, "Page", "Person" + i, LocalDate.of(2024, 4, 1), engineering));
        }
        entityManager.flush();
        entityManager.clear();

        Page<Employee> firstPage = employees.findAll(
                EmployeeSpecifications.matching(new EmployeeSearch(
                        "page", null, null, null, StatusFilter.ACTIVE_ONLY)),
                PageRequest.of(0, 2, Sort.by("employeeCode")));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
    }

    @Test
    void uniquenessPreChecksAnswerBeforeTheDatabaseHasTo() {
        employees.save(employee("UQ-001", "Asha", "Menon", LocalDate.of(2024, 4, 1), engineering));
        entityManager.flush();

        assertThat(employees.existsByEmployeeCode("UQ-001")).isTrue();
        assertThat(employees.existsByEmployeeCode("UQ-999")).isFalse();
        assertThat(employees.existsByWorkEmail("uq-001@acme.test")).isTrue();
        assertThat(employees.findByEmployeeCode("UQ-001")).isPresent();
    }

    @Test
    void anEmployeeCanBeFoundByTheLoginLinkedToThem() {
        Employee saved = employees.save(employee("LK-001", "Asha", "Menon", LocalDate.of(2024, 4, 1), engineering));
        saved.linkUser(4242L);
        entityManager.flush();
        entityManager.clear();

        // No FK to a real user row is asserted here — the id is deliberately not an
        // association, so this query does not drag the auth feature into the test.
        assertThat(employees.findByUserId(4242L)).map(Employee::getEmployeeCode).contains("LK-001");
    }

    @Test
    void countsActiveStaffPerDepartmentAndIgnoresLeavers() {
        employees.save(employee("CT-001", "One", "Person", LocalDate.of(2024, 4, 1), finance));
        employees.save(employee("CT-002", "Two", "Person", LocalDate.of(2024, 4, 1), finance));
        Employee leaver = employees.save(employee("CT-003", "Three", "Person", LocalDate.of(2024, 4, 1), finance));
        leaver.deactivate(LocalDate.of(2026, 1, 31));
        entityManager.flush();
        entityManager.clear();

        assertThat(employees.countActiveByDepartment(finance.getId())).isEqualTo(2);
        assertThat(employees.countByStatus(EmployeeStatus.INACTIVE)).isEqualTo(1);
    }

    private Employee employee(String code, String first, String last, LocalDate joined, Department department) {
        return new Employee(code, first, last, code.toLowerCase() + "@acme.test",
                joined, department, engineer, grade);
    }

    private static void setFieldValue(Object target, String fieldName, Object value) {
        try {
            Field field = Employee.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not set " + fieldName, e);
        }
    }
}
