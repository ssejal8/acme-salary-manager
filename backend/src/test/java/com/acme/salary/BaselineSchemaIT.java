package com.acme.salary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the application against real PostgreSQL and asserts that the baseline migration
 * applies and that the constraints the design relies on actually hold (ADR-012).
 *
 * <p>These are the invariants a service-layer pre-check cannot guarantee under a race, so
 * verifying them here is the point of the test — an in-memory database would happily
 * accept most of what this asserts is rejected.
 *
 * <p>Skipped, not failed, where Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class BaselineSchemaIT {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the Testcontainers extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("salary_mgmt")
            .withUsername("salary_app")
            .withPassword("salary_app_test");

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void baselineMigrationIsApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);

        assertThat(versions).contains("1");
    }

    @Test
    void everyExpectedTableExists() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "users", "departments", "designations", "grades", "employees",
                "salary_components", "salary_structures", "salary_structure_components",
                "payroll_runs", "payslips", "payslip_lines", "audit_events");
    }

    @Test
    void noColumnStoresMoneyAsAFloatOrAtTheWrongScale() {
        // ADR-006: a float column anywhere, or a NUMERIC at another scale, would defeat
        // exact money at the storage layer regardless of what the Java side does.
        List<String> offenders = jdbc.queryForList("""
                SELECT table_name || '.' || column_name || ' (' || data_type || ')'
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (data_type IN ('real', 'double precision')
                       OR (data_type = 'numeric' AND numeric_scale <> 2))
                """, String.class);

        assertThat(offenders).isEmpty();
    }

    @Test
    void duplicateEmployeeCodeIsRejected() {
        long departmentId = insertDepartment();
        long designationId = insertDesignation();
        long gradeId = insertGrade();
        String code = "E-" + SEQ.incrementAndGet();

        insertEmployee(code, "first" + code + "@acme.test", departmentId, designationId, gradeId);

        assertThatThrownBy(() -> insertEmployee(
                code, "second" + code + "@acme.test", departmentId, designationId, gradeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inactiveEmployeeWithoutAnExitDateIsRejected() {
        // Payroll eligibility is derived from the exit date (FR-2.6), so a leaver without
        // one would silently stay in the next run.
        long departmentId = insertDepartment();
        long designationId = insertDesignation();
        long gradeId = insertGrade();
        String code = "E-" + SEQ.incrementAndGet();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO employees (employee_code, first_name, last_name, work_email,
                                       date_of_joining, status, department_id, designation_id, grade_id)
                VALUES (?, 'Leaver', 'Test', ?, DATE '2024-01-01', 'INACTIVE', ?, ?, ?)
                """, code, code + "@acme.test", departmentId, designationId, gradeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void referenceDataInUseCannotBeDeleted() {
        // FR-3.3, enforced by ON DELETE RESTRICT rather than by a service-layer check.
        long departmentId = insertDepartment();
        long designationId = insertDesignation();
        long gradeId = insertGrade();
        String code = "E-" + SEQ.incrementAndGet();
        insertEmployee(code, code + "@acme.test", departmentId, designationId, gradeId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM departments WHERE id = ?", departmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPeriodAcceptsOnlyOneLiveRunButRetriesAfterCancellation() {
        // FR-5.7, and the reason the index is partial rather than a plain UNIQUE.
        long userId = insertUser();
        int year = 2030 + SEQ.incrementAndGet();
        long firstRun = insertRun(year, 3, "DRAFT", userId);

        assertThatThrownBy(() -> insertRun(year, 3, "DRAFT", userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE payroll_runs SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?", firstRun);

        assertThatCode(() -> insertRun(year, 3, "DRAFT", userId)).doesNotThrowAnyException();
    }

    @Test
    void aPayslipTotalMustEqualItsGrossMinusDeductions() {
        long userId = insertUser();
        long departmentId = insertDepartment();
        long designationId = insertDesignation();
        long gradeId = insertGrade();
        String code = "E-" + SEQ.incrementAndGet();
        long employeeId = insertEmployee(code, code + "@acme.test", departmentId, designationId, gradeId);
        long runId = insertRun(2035 + SEQ.incrementAndGet(), 4, "DRAFT", userId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payslips (payroll_run_id, employee_id, total_days, paid_days, lop_days,
                                      gross_pay, total_deductions, net_pay)
                VALUES (?, ?, 30, 30, 0, 70000.00, 6000.00, 99999.00)
                """, runId, employeeId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc.update("""
                INSERT INTO payslips (payroll_run_id, employee_id, total_days, paid_days, lop_days,
                                      gross_pay, total_deductions, net_pay)
                VALUES (?, ?, 30, 30, 0, 70000.00, 6000.00, 64000.00)
                """, runId, employeeId))
                .doesNotThrowAnyException();
    }

    @Test
    void paidDaysAndLopDaysMustAccountForTheWholePeriod() {
        long userId = insertUser();
        long departmentId = insertDepartment();
        long designationId = insertDesignation();
        long gradeId = insertGrade();
        String code = "E-" + SEQ.incrementAndGet();
        long employeeId = insertEmployee(code, code + "@acme.test", departmentId, designationId, gradeId);
        long runId = insertRun(2040 + SEQ.incrementAndGet(), 5, "DRAFT", userId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payslips (payroll_run_id, employee_id, total_days, paid_days, lop_days,
                                      gross_pay, total_deductions, net_pay)
                VALUES (?, ?, 30, 25, 3, 50000.00, 0.00, 50000.00)
                """, runId, employeeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmployeeHasAtMostOneCurrentSalaryStructure() {
        // ADR-009: revisions supersede, so exactly one revision may be left open.
        long userId = insertUser();
        long departmentId = insertDepartment();
        long designationId = insertDesignation();
        long gradeId = insertGrade();
        String code = "E-" + SEQ.incrementAndGet();
        long employeeId = insertEmployee(code, code + "@acme.test", departmentId, designationId, gradeId);

        jdbc.update("""
                INSERT INTO salary_structures (employee_id, effective_from, created_by)
                VALUES (?, DATE '2026-01-01', ?)
                """, employeeId, userId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO salary_structures (employee_id, effective_from, created_by)
                VALUES (?, DATE '2026-06-01', ?)
                """, employeeId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE salary_structures SET superseded_on = DATE '2026-06-01'
                WHERE employee_id = ? AND superseded_on IS NULL
                """, employeeId);

        assertThatCode(() -> jdbc.update("""
                INSERT INTO salary_structures (employee_id, effective_from, created_by)
                VALUES (?, DATE '2026-06-01', ?)
                """, employeeId, userId))
                .doesNotThrowAnyException();
    }

    @Test
    void aPercentageComponentCannotExceedOneHundredPercent() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO salary_components (code, name, type, calculation_type, default_value)
                VALUES (?, 'Impossible deduction', 'DEDUCTION', 'PERCENT_OF_BASIC', 120.00)
                """, "BAD_PCT_" + SEQ.incrementAndGet()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- fixtures -------------------------------------------------------------------

    private long insertUser() {
        int seq = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, role)
                VALUES (?, '$2a$10$notarealhashusedonlyintests000000000000000000000000000', 'HR')
                RETURNING id
                """, Long.class, "hr" + seq + "@acme.test");
    }

    private long insertDepartment() {
        int seq = SEQ.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO departments (code, name) VALUES (?, ?) RETURNING id",
                Long.class, "D" + seq, "Department " + seq);
    }

    private long insertDesignation() {
        int seq = SEQ.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO designations (title) VALUES (?) RETURNING id",
                Long.class, "Designation " + seq);
    }

    private long insertGrade() {
        int seq = SEQ.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO grades (name, min_ctc, max_ctc) VALUES (?, 400000.00, 800000.00) RETURNING id",
                Long.class, "G" + seq);
    }

    private long insertEmployee(
            String code, String email, long departmentId, long designationId, long gradeId) {
        return jdbc.queryForObject("""
                INSERT INTO employees (employee_code, first_name, last_name, work_email,
                                       date_of_joining, department_id, designation_id, grade_id)
                VALUES (?, 'Test', 'Employee', ?, DATE '2024-01-01', ?, ?, ?)
                RETURNING id
                """, Long.class, code, email, departmentId, designationId, gradeId);
    }

    private long insertRun(int year, int month, String status, long userId) {
        return jdbc.queryForObject("""
                INSERT INTO payroll_runs (period_year, period_month, status, initiated_by)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, year, month, status, userId);
    }
}
