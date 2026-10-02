package com.acme.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies the dev seed to a real database and checks it lands.
 *
 * <p>{@link com.acme.salary.report.DevSeedFiguresTest} already verifies the seed's
 * arithmetic without a database. What only a database can tell us is whether the SQL is
 * valid, whether it satisfies the schema's constraints, and whether the identity sequences
 * were advanced past the explicit ids — the failure that would otherwise appear as a
 * duplicate-key error the first time someone creates an employee through the API.
 *
 * <p>Skipped, not failed, where Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
@Testcontainers(disabledWithoutDocker = true)
class DevSeedIT {

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
    private JdbcTemplate jdbc;

    @Test
    void bothSeedMigrationsApply() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class);

        assertThat(versions).contains("1", "2", "900", "901", "902");
    }

    @Test
    void seedsTwelveCuratedEmployeesWithStableIds() {
        // Deterministic ids are the point: a demo or a test may name employee 1001. The
        // curated twelve are ids 1001-1012; V902 generates everyone above them, so these
        // counts are scoped to that range rather than to the table.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM employees WHERE id <= 1012", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject(
                "SELECT employee_code FROM employees WHERE id = 1001", String.class))
                .isEqualTo("E-1001");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM employees WHERE id <= 1012 AND status = 'ACTIVE'",
                Integer.class))
                .isEqualTo(11);
    }

    @Test
    void theBulkSeedTakesTheDatasetToTenThousand() {
        // The same figures the CI seed job and the README assert.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Integer.class))
                .isEqualTo(10000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM employees WHERE id > 1012 AND status = 'INACTIVE'",
                Integer.class))
                .isEqualTo(102);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salary_structures", Integer.class))
                .isEqualTo(9960);
    }

    @Test
    void theLeaverKeepsAnExitDateAsTheSchemaRequires() {
        assertThat(jdbc.queryForObject(
                "SELECT exit_date::text FROM employees WHERE employee_code = 'E-1012'", String.class))
                .isEqualTo("2026-03-31");
    }

    @Test
    void seedsElevenCuratedRevisionsWithExactlyOneRaiseHistory() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM salary_structures WHERE employee_id <= 1012", Integer.class))
                .isEqualTo(11);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM salary_structures WHERE employee_id = 1001", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT superseded_on::text FROM salary_structures WHERE id = 5001", String.class))
                .isEqualTo("2024-04-01");
    }

    @Test
    void everyEmployeeHasAtMostOneOpenRevision() {
        // The partial unique index would have rejected the insert; this asserts the shape
        // the seed intended rather than merely that it did not blow up.
        Integer employeesWithTwoOpenRevisions = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT employee_id FROM salary_structures
                    WHERE superseded_on IS NULL
                    GROUP BY employee_id HAVING count(*) > 1
                ) AS offenders
                """, Integer.class);

        assertThat(employeesWithTwoOpenRevisions).isZero();
    }

    @Test
    void theEmployeeLoginIsLinkedToItsEmployee() {
        assertThat(jdbc.queryForObject("""
                SELECT u.role FROM employees e JOIN users u ON u.id = e.user_id
                WHERE e.employee_code = 'E-1001'
                """, String.class)).isEqualTo("EMPLOYEE");
    }

    @Test
    void sequencesAreAdvancedPastTheSeededIds() {
        // Without the setval calls at the end of the seed, this insert would be handed
        // id 1 and eventually collide with the seeded rows.
        Long nextEmployeeId = jdbc.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('employees', 'id'))", Long.class);
        Long nextStructureId = jdbc.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('salary_structures', 'id'))", Long.class);

        // Past V902's ids, which are the highest seeded.
        assertThat(nextEmployeeId).isGreaterThan(11000L);
        assertThat(nextStructureId).isGreaterThan(29988L);
    }

    @Test
    // Rolled back: every test in this class shares one database, so a committed insert
    // here made the headcount assertions depend on test order (10,001, not 10,000).
    @Transactional
    void anEmployeeCanStillBeCreatedThroughTheOrdinaryPath() {
        assertThatCode(() -> jdbc.update("""
                INSERT INTO employees (employee_code, first_name, last_name, work_email,
                                       date_of_joining, department_id, designation_id, grade_id)
                SELECT 'POST-SEED-1', 'Post', 'Seed', 'post.seed@acme.test', DATE '2026-09-01',
                       d.id, g.id, gr.id
                FROM departments d, designations g, grades gr
                WHERE d.code = 'ENG' AND g.title = 'Software Engineer' AND gr.name = 'G1'
                """)).doesNotThrowAnyException();
    }

    @Test
    void theSeededCostMatchesWhatTheSeedDocuments() {
        // Recomputed in SQL as an independent check on the figures the application-side
        // test asserts: flat earnings plus percentage deductions against basic.
        BigDecimal activeGross = jdbc.queryForObject("""
                SELECT COALESCE(SUM(line.value), 0)
                FROM salary_structures s
                JOIN employees e ON e.id = s.employee_id
                JOIN salary_structure_components line ON line.structure_id = s.id
                JOIN salary_components c ON c.id = line.component_id
                WHERE s.superseded_on IS NULL
                  AND e.status = 'ACTIVE'
                  AND e.id <= 1012
                  AND c.type = 'EARNING'
                  AND c.calculation_type = 'FLAT'
                """, BigDecimal.class);

        assertThat(activeGross).isEqualByComparingTo("950000.00");
    }

    @Test
    void everySeededStructureIsAttributedToTheHrUser() {
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT u.email) FROM salary_structures s
                JOIN users u ON u.id = s.created_by
                """, Integer.class)).isEqualTo(1);
    }
}
