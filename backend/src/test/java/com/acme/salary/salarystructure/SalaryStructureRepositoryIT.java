package com.acme.salary.salarystructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.persistence.JpaAuditingConfig;
import com.acme.salary.employee.Employee;
import com.acme.salary.employee.EmployeeRepository;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.DepartmentRepository;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.DesignationRepository;
import com.acme.salary.orgdata.Grade;
import com.acme.salary.orgdata.GradeRepository;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarycomponent.SalaryComponent;
import com.acme.salary.salarycomponent.SalaryComponentRepository;
import com.acme.salary.security.Role;
import com.acme.salary.security.User;
import com.acme.salary.security.UserRepository;
import com.acme.salary.support.ClockTestConfig;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Salary structure persistence against real PostgreSQL: the revision history, the
 * "one open revision" index, and the effective-date resolution a payroll run will rely on.
 *
 * <p>Skipped, not failed, where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SalaryStructureRepositoryIT {

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
    private SalaryStructureRepository structures;

    @Autowired
    private SalaryComponentRepository components;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private DepartmentRepository departments;

    @Autowired
    private DesignationRepository designations;

    @Autowired
    private GradeRepository grades;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestEntityManager entityManager;

    private Long employeeId;
    private Long actorId;
    private SalaryComponent basic;
    private SalaryComponent providentFund;

    @BeforeEach
    void setUp() {
        Department department = departments.save(new Department("ENG", "Engineering"));
        Designation designation = designations.save(new Designation("Software Engineer"));
        Grade grade = grades.save(new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000")));
        Employee employee = employees.save(new Employee("IT-100", "Asha", "Menon", "it-100@acme.test",
                LocalDate.of(2024, 4, 1), department, designation, grade));
        employeeId = employee.getId();
        actorId = users.save(new User("hr-it@acme.test", "$2a$10$notarealhashusedonlyintests000", Role.HR))
                .getId();

        basic = components.save(new SalaryComponent("BASIC", "Basic Salary",
                ComponentType.EARNING, CalculationType.FLAT, BigDecimal.ZERO, true));
        providentFund = components.save(new SalaryComponent("PF", "Provident Fund",
                ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC, new BigDecimal("12"), false));
        entityManager.flush();
    }

    private SalaryStructure structure(LocalDate effectiveFrom, String basicAmount) {
        SalaryStructure structure = new SalaryStructure(employeeId, effectiveFrom, actorId, null);
        structure.addComponent(basic, new BigDecimal(basicAmount));
        structure.addComponent(providentFund, new BigDecimal("12"));
        return structure;
    }

    @Test
    void savesAStructureWithItsComponentsAndComputesTotalsAfterReload() {
        SalaryStructure saved = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();
        entityManager.clear();

        SalaryStructure reloaded = structures.findCurrent(employeeId).orElseThrow();

        assertThat(reloaded.getComponents()).hasSize(2);
        assertThat(reloaded.getCreatedBy()).isEqualTo(actorId);
        assertThat(reloaded.getCreatedAt()).isEqualTo(ClockTestConfig.FIXED_INSTANT);
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        // 50,000 basic less 12% provident fund.
        assertThat(reloaded.totals().grossMonthly()).isEqualByComparingTo("50000.00");
        assertThat(reloaded.totals().totalDeductions()).isEqualByComparingTo("6000.00");
        assertThat(reloaded.totals().netMonthly()).isEqualByComparingTo("44000.00");
    }

    @Test
    void anEmployeeMayHaveOnlyOneOpenRevision() {
        // The partial unique index is the real guard; the service's supersede step is what
        // keeps it satisfied (ADR-009).
        structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();

        structures.save(structure(LocalDate.of(2026, 10, 1), "60000"));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void supersedingLetsTheNextRevisionOpen() {
        SalaryStructure first = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();

        first.supersede(LocalDate.of(2026, 10, 1));
        structures.save(structure(LocalDate.of(2026, 10, 1), "60000"));
        entityManager.flush();
        entityManager.clear();

        List<SalaryStructure> history = structures.findHistory(employeeId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(history.get(0).isCurrent()).isTrue();
        assertThat(history.get(1).getSupersededOn()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(history.get(1).isCurrent()).isFalse();
    }

    @Test
    void aSupersededRevisionKeepsItsOwnFigures() {
        // The point of append-only revisions: payroll for March still sees March's package.
        SalaryStructure first = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();
        first.supersede(LocalDate.of(2026, 10, 1));
        structures.save(structure(LocalDate.of(2026, 10, 1), "60000"));
        entityManager.flush();
        entityManager.clear();

        SalaryStructure older = structures.findEffectiveOn(employeeId, LocalDate.of(2026, 9, 30)).orElseThrow();
        SalaryStructure newer = structures.findEffectiveOn(employeeId, LocalDate.of(2026, 10, 1)).orElseThrow();

        assertThat(older.totals().grossMonthly()).isEqualByComparingTo("50000.00");
        assertThat(newer.totals().grossMonthly()).isEqualByComparingTo("60000.00");
    }

    @Test
    void findEffectiveOnIgnoresRevisionsThatHaveNotStartedYet() {
        structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();
        entityManager.clear();

        assertThat(structures.findEffectiveOn(employeeId, LocalDate.of(2026, 3, 31))).isEmpty();
        assertThat(structures.findEffectiveOn(employeeId, LocalDate.of(2026, 4, 1))).isPresent();
    }

    @Test
    void twoRevisionsCannotShareAnEffectiveDate() {
        SalaryStructure first = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();
        first.supersede(LocalDate.of(2026, 10, 1));
        entityManager.flush();

        structures.save(structure(LocalDate.of(2026, 4, 1), "60000"));

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aStructureCannotBeSupersededBeforeItTakesEffect() {
        SalaryStructure structure = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();

        assertThatThrownBy(() -> structure.supersede(LocalDate.of(2026, 3, 1)))
                .isInstanceOf(com.acme.salary.common.error.ValidationException.class);
    }

    @Test
    void aPersistedStructureRefusesFurtherComponents() {
        SalaryStructure structure = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();

        // ADR-009 in code: amounts on a saved revision are immutable, which is what makes
        // FR-4.7 hold once a payroll run has used one.
        assertThatThrownBy(() -> structure.addComponent(basic, new BigDecimal("1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void structureComponentsAreDeletedWithTheirStructureButComponentDefinitionsSurvive() {
        SalaryStructure structure = structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();

        structures.delete(structure);
        entityManager.flush();
        entityManager.clear();

        // Definitions are reference data: ON DELETE RESTRICT keeps them safe, and the
        // cascade only reaches the structure's own lines.
        assertThat(components.findByCode("BASIC")).isPresent();
        assertThat(structures.findCurrent(employeeId)).isEmpty();
    }

    @Test
    void componentDefinitionsInUseCannotBeDeleted() {
        structures.save(structure(LocalDate.of(2026, 4, 1), "50000"));
        entityManager.flush();

        components.delete(providentFund);

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aStructureMustBeAttributedToARealUser() {
        SalaryStructure orphan = new SalaryStructure(employeeId, LocalDate.of(2026, 4, 1), 999_999L, null);
        orphan.addComponent(basic, new BigDecimal("50000"));
        structures.save(orphan);

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }
}
