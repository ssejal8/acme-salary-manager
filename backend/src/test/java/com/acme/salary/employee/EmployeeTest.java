package com.acme.salary.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.Grade;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Employee invariants, tested without Spring or a database — the domain rules are pure,
 * so they need neither.
 */
class EmployeeTest {

    private static final LocalDate JOINED = LocalDate.of(2024, 4, 1);

    private final Department department = new Department("eng", "Engineering");
    private final Designation designation = new Designation("Software Engineer");
    private final Grade grade = new Grade("G2", new BigDecimal("800000"), new BigDecimal("1500000"));

    private Employee newEmployee() {
        return new Employee("e-001", "Asha", "Menon", "Asha.Menon@ACME.test",
                JOINED, department, designation, grade);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void startsActiveWithNoExitDate() {
            Employee employee = newEmployee();

            assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(employee.isActive()).isTrue();
            assertThat(employee.getExitDate()).isNull();
        }

        @Test
        void canonicalisesCodeAndEmail() {
            Employee employee = newEmployee();

            // Codes appear on payslips and emails become usernames, so both are stored in
            // one canonical form.
            assertThat(employee.getEmployeeCode()).isEqualTo("E-001");
            assertThat(employee.getWorkEmail()).isEqualTo("asha.menon@acme.test");
        }

        @Test
        void buildsFullName() {
            assertThat(newEmployee().fullName()).isEqualTo("Asha Menon");
        }

        @Test
        void rejectsBlankRequiredFields() {
            assertThatThrownBy(() -> new Employee("  ", "Asha", "Menon", "a@acme.test",
                    JOINED, department, designation, grade))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Validation failed");

            assertThatThrownBy(() -> new Employee("E-002", "", "Menon", "a@acme.test",
                    JOINED, department, designation, grade))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void rejectsMalformedEmailWithAFieldMessage() {
            assertThatThrownBy(() -> new Employee("E-003", "Asha", "Menon", "not-an-email",
                    JOINED, department, designation, grade))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(fieldError -> assertThat(fieldError.field()).isEqualTo("workEmail")));
        }

        @Test
        void rejectsMissingReferenceData() {
            assertThatThrownBy(() -> new Employee("E-004", "Asha", "Menon", "a@acme.test",
                    JOINED, null, designation, grade))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void rejectsMissingDateOfJoining() {
            assertThatThrownBy(() -> new Employee("E-005", "Asha", "Menon", "a@acme.test",
                    null, department, designation, grade))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("deactivation")
    class Deactivation {

        @Test
        void recordsExitDateAndFlipsStatus() {
            Employee employee = newEmployee();

            employee.deactivate(LocalDate.of(2026, 3, 31));

            assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
            assertThat(employee.getExitDate()).isEqualTo(LocalDate.of(2026, 3, 31));
            assertThat(employee.isActive()).isFalse();
        }

        @Test
        void rejectsAnExitDateBeforeJoining() {
            Employee employee = newEmployee();

            assertThatThrownBy(() -> employee.deactivate(JOINED.minusDays(1)))
                    .isInstanceOf(ValidationException.class);
            assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        }

        @Test
        void allowsExitOnTheJoiningDate() {
            Employee employee = newEmployee();

            employee.deactivate(JOINED);

            assertThat(employee.getExitDate()).isEqualTo(JOINED);
        }

        @Test
        void reactivationClearsTheExitDate() {
            // The schema requires an INACTIVE employee to carry an exit date, so the two
            // fields must always move together.
            Employee employee = newEmployee();
            employee.deactivate(LocalDate.of(2026, 3, 31));

            employee.reactivate();

            assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(employee.getExitDate()).isNull();
        }
    }

    @Nested
    @DisplayName("payroll eligibility")
    class PayrollEligibility {

        private final LocalDate periodStart = LocalDate.of(2026, 9, 1);
        private final LocalDate periodEnd = LocalDate.of(2026, 9, 30);

        @Test
        void activeEmployeeWhoJoinedEarlierIsEligible() {
            assertThat(newEmployee().isEligibleForPayroll(periodStart, periodEnd)).isTrue();
        }

        @Test
        void employeeJoiningMidPeriodIsEligible() {
            Employee employee = new Employee("E-010", "Mid", "Joiner", "mid@acme.test",
                    LocalDate.of(2026, 9, 15), department, designation, grade);

            assertThat(employee.isEligibleForPayroll(periodStart, periodEnd)).isTrue();
        }

        @Test
        void employeeJoiningAfterThePeriodIsNotEligible() {
            Employee employee = new Employee("E-011", "Future", "Joiner", "future@acme.test",
                    LocalDate.of(2026, 10, 1), department, designation, grade);

            assertThat(employee.isEligibleForPayroll(periodStart, periodEnd)).isFalse();
        }

        @Test
        void leaverWhoExitedBeforeThePeriodIsExcluded() {
            // FR-2.6: excluded from runs for periods beginning after the exit date.
            Employee employee = newEmployee();
            employee.deactivate(LocalDate.of(2026, 8, 31));

            assertThat(employee.isEligibleForPayroll(periodStart, periodEnd)).isFalse();
        }

        @Test
        void leaverWhoExitsDuringThePeriodIsStillPaid() {
            Employee employee = newEmployee();
            employee.deactivate(LocalDate.of(2026, 9, 20));

            assertThat(employee.isEligibleForPayroll(periodStart, periodEnd)).isTrue();
        }

        @Test
        void leaverWhoExitsOnTheFirstDayOfThePeriodIsStillPaid() {
            Employee employee = newEmployee();
            employee.deactivate(periodStart);

            assertThat(employee.isEligibleForPayroll(periodStart, periodEnd)).isTrue();
        }
    }

    @Nested
    @DisplayName("updates")
    class Updates {

        @Test
        void editableFieldsChangeAndCanonicalisationStillApplies() {
            Employee employee = newEmployee();
            Department finance = new Department("FIN", "Finance");

            employee.updateDetails("Asha", "Menon-Rao", "Asha.Menon.Rao@Acme.Test",
                    finance, designation, grade);

            assertThat(employee.getLastName()).isEqualTo("Menon-Rao");
            assertThat(employee.getWorkEmail()).isEqualTo("asha.menon.rao@acme.test");
            assertThat(employee.getDepartment()).isEqualTo(finance);
        }

        @Test
        void identityFieldsAreNotUpdatable() {
            // FR-2.3: no setter exists for either, and the mapping marks both
            // updatable = false so a stray change cannot reach the database.
            assertThat(Employee.class.getDeclaredMethods())
                    .noneMatch(method -> method.getName().equals("setEmployeeCode")
                            || method.getName().equals("setDateOfJoining"));
        }

        @Test
        void linkingALoginRequiresAnId() {
            Employee employee = newEmployee();

            employee.linkUser(7L);
            assertThat(employee.getUserId()).isEqualTo(7L);

            assertThatThrownBy(() -> employee.linkUser(null)).isInstanceOf(ValidationException.class);
        }
    }
}
