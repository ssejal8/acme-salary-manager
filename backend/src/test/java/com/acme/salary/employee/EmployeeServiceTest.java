package com.acme.salary.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ApiError;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import com.acme.salary.employee.dto.CreateEmployeeRequest;
import com.acme.salary.employee.dto.DeactivateEmployeeRequest;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import com.acme.salary.employee.dto.UpdateEmployeeRequest;
import com.acme.salary.orgdata.DepartmentRepository;
import com.acme.salary.orgdata.DesignationRepository;
import com.acme.salary.orgdata.GradeRepository;
import com.acme.salary.support.EmployeeFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Service behaviour with a mocked repository — no Spring context, no database. */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository employees;

    @Mock
    private DepartmentRepository departments;

    @Mock
    private DesignationRepository designations;

    @Mock
    private GradeRepository grades;

    @Mock
    private AuditService audit;

    @InjectMocks
    private EmployeeService service;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    /** Typed matcher, so the tests stay free of raw-type warnings. */
    private static Specification<Employee> anySpecification() {
        return any();
    }

    @Test
    void mapsEachRowToASummaryResponse() {
        Employee employee = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
        when(employees.findAll(anySpecification(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(employee), PageRequest.of(0, 20), 1));

        PageResponse<EmployeeSummaryResponse> response =
                service.search(EmployeeSearch.activeEmployees(), PageRequest.of(0, 20));

        assertThat(response.content()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(1L);
            assertThat(row.employeeCode()).isEqualTo("E-001");
            assertThat(row.fullName()).isEqualTo("Asha Menon");
            assertThat(row.workEmail()).isEqualTo("e-001@acme.test");
            assertThat(row.status()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(row.department().label()).isEqualTo("Engineering");
            assertThat(row.designation().label()).isEqualTo("Software Engineer");
            assertThat(row.grade().label()).isEqualTo("G2");
        });
    }

    @Test
    void reportsPageMetadataFromTheRepositoryPage() {
        List<Employee> rows = List.of(
                EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon"),
                EmployeeFixtures.employee(2L, "E-002", "Ravi", "Menon"));
        when(employees.findAll(anySpecification(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(1, 2), 7));

        PageResponse<EmployeeSummaryResponse> response =
                service.search(EmployeeSearch.activeEmployees(), PageRequest.of(1, 2));

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.hasPrevious()).isTrue();
    }

    @Test
    void passesThePageRequestStraightToTheRepository() {
        // Paging must reach the database rather than being applied to a loaded list
        // (NFR-1.2).
        when(employees.findAll(anySpecification(), any(Pageable.class)))
                .thenReturn(Page.empty());
        Pageable requested = PageRequest.of(3, 50, Sort.by("employeeCode"));

        service.search(EmployeeSearch.activeEmployees(), requested);

        verify(employees).findAll(anySpecification(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue()).isEqualTo(requested);
    }

    @Test
    void anEmptyPageIsAnEmptyResponseNotAnError() {
        when(employees.findAll(anySpecification(), any(Pageable.class))).thenReturn(Page.empty());

        PageResponse<EmployeeSummaryResponse> response = service.search(
                new EmployeeSearch("nobody", null, null, null, StatusFilter.ALL), PageRequest.of(0, 20));

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void findByIdReturnsTheSummary() {
        when(employees.findWithReferencesById(1L))
                .thenReturn(Optional.of(EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon")));

        assertThat(service.findById(1L).employeeCode()).isEqualTo("E-001");
    }

    @Test
    void findByIdOnAnUnknownEmployeeIsANotFound() {
        when(employees.findWithReferencesById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Employee 99");
    }

    /** Creating a record (FR-2.1), and the uniqueness rules around it (FR-2.2). */
    @Nested
    @DisplayName("creating an employee")
    class Creating {

        private CreateEmployeeRequest request() {
            return new CreateEmployeeRequest(
                    "E-050", "Asha", "Menon", "asha.menon@acme.test",
                    LocalDate.of(2026, 4, 1), 10L, 20L, 30L);
        }

        private void referencesExist() {
            when(departments.findById(10L))
                    .thenReturn(Optional.of(EmployeeFixtures.department(10L, "ENG", "Engineering")));
            when(designations.findById(20L))
                    .thenReturn(Optional.of(EmployeeFixtures.designation(20L, "Software Engineer")));
            when(grades.findById(30L)).thenReturn(Optional.of(EmployeeFixtures.grade(30L, "G2")));
        }

        @Test
        void savesAnActiveEmployeeWithTheGivenDetails() {
            referencesExist();
            when(employees.save(any(Employee.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 7L));

            EmployeeSummaryResponse created = service.create(request());

            assertThat(created.id()).isEqualTo(7L);
            assertThat(created.employeeCode()).isEqualTo("E-050");
            assertThat(created.fullName()).isEqualTo("Asha Menon");
            assertThat(created.status()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(created.exitDate()).isNull();
        }

        @Test
        void normalisesTheCodeAndEmailBeforeCheckingForDuplicates() {
            // The bug this guards: a pre-check against the raw input lets "e-050" past a
            // lookup for "E-050", and the duplicate then surfaces as a database error
            // instead of a field-level 409.
            referencesExist();
            when(employees.save(any(Employee.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 7L));

            service.create(new CreateEmployeeRequest(
                    " e-050 ", "Asha", "Menon", " Asha.Menon@ACME.test ",
                    LocalDate.of(2026, 4, 1), 10L, 20L, 30L));

            verify(employees).existsByEmployeeCode("E-050");
            verify(employees).existsByWorkEmail("asha.menon@acme.test");
        }

        @Test
        void aDuplicateCodeIsAConflictNamingTheField() {
            when(employees.existsByEmployeeCode("E-050")).thenReturn(true);

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(thrown -> assertThat(((ConflictException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> {
                                assertThat(error.field()).isEqualTo("employeeCode");
                                assertThat(error.message()).contains("E-050");
                            }));
        }

        @Test
        void reportsBothDuplicatesTogetherRatherThanStoppingAtTheFirst() {
            // So the form does not have to be resubmitted to learn the second fact.
            when(employees.existsByEmployeeCode("E-050")).thenReturn(true);
            when(employees.existsByWorkEmail("asha.menon@acme.test")).thenReturn(true);

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(thrown -> assertThat(((ConflictException) thrown).fieldErrors())
                            .extracting(ApiError.FieldError::field)
                            .containsExactly("employeeCode", "workEmail"));
        }

        @Test
        void anUnknownDepartmentIsAValidationErrorNamingThatField() {
            when(departments.findById(10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request()))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("departmentId")));
        }

        @Test
        void recordsWhoCreatedTheRecord() {
            referencesExist();
            when(employees.save(any(Employee.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 7L));

            service.create(request());

            verify(audit).record(eq(AuditEntityType.EMPLOYEE), eq(7L),
                    eq(AuditAction.EMPLOYEE_CREATED), anyMap());
        }
    }

    /** Updating the editable fields (FR-2.3). */
    @Nested
    @DisplayName("updating an employee")
    class Updating {

        private final UpdateEmployeeRequest request = new UpdateEmployeeRequest(
                "Asha", "Menon-Rao", "asha.rao@acme.test", 10L, 20L, 30L);

        @Test
        void appliesTheEditableFields() {
            Employee existing = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(existing));
            when(departments.findById(10L))
                    .thenReturn(Optional.of(EmployeeFixtures.department(10L, "FIN", "Finance")));
            when(designations.findById(20L))
                    .thenReturn(Optional.of(EmployeeFixtures.designation(20L, "Finance Analyst")));
            when(grades.findById(30L)).thenReturn(Optional.of(EmployeeFixtures.grade(30L, "G3")));

            EmployeeSummaryResponse updated = service.update(1L, request);

            assertThat(updated.lastName()).isEqualTo("Menon-Rao");
            assertThat(updated.workEmail()).isEqualTo("asha.rao@acme.test");
            assertThat(updated.department().label()).isEqualTo("Finance");
        }

        @Test
        void leavesTheCodeAndJoiningDateAlone() {
            // FR-2.3: both are immutable after creation. The request cannot carry them,
            // and this is the assertion that says so out loud.
            Employee existing = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(existing));
            when(departments.findById(10L))
                    .thenReturn(Optional.of(EmployeeFixtures.department(10L, "ENG", "Engineering")));
            when(designations.findById(20L))
                    .thenReturn(Optional.of(EmployeeFixtures.designation(20L, "Software Engineer")));
            when(grades.findById(30L)).thenReturn(Optional.of(EmployeeFixtures.grade(30L, "G2")));

            EmployeeSummaryResponse updated = service.update(1L, request);

            assertThat(updated.employeeCode()).isEqualTo("E-001");
            assertThat(updated.dateOfJoining()).isEqualTo(LocalDate.of(2024, 4, 1));
        }

        @Test
        void anEmailTakenByAnotherEmployeeIsAConflict() {
            when(employees.findWithReferencesById(1L))
                    .thenReturn(Optional.of(EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon")));
            when(employees.existsByWorkEmailAndIdNot("asha.rao@acme.test", 1L)).thenReturn(true);

            assertThatThrownBy(() -> service.update(1L, request))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(thrown -> assertThat(((ConflictException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("workEmail")));
        }

        @Test
        void keepingYourOwnEmailIsNotAConflictWithYourself() {
            // The reason the check excludes this record: saving a form that did not touch
            // the email must not be refused.
            Employee existing = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(existing));
            when(employees.existsByWorkEmailAndIdNot("e-001@acme.test", 1L)).thenReturn(false);
            when(departments.findById(10L))
                    .thenReturn(Optional.of(EmployeeFixtures.department(10L, "ENG", "Engineering")));
            when(designations.findById(20L))
                    .thenReturn(Optional.of(EmployeeFixtures.designation(20L, "Software Engineer")));
            when(grades.findById(30L)).thenReturn(Optional.of(EmployeeFixtures.grade(30L, "G2")));

            EmployeeSummaryResponse updated = service.update(1L, new UpdateEmployeeRequest(
                    "Asha", "Menon", "e-001@acme.test", 10L, 20L, 30L));

            assertThat(updated.workEmail()).isEqualTo("e-001@acme.test");
        }

        @Test
        void anUnknownEmployeeIsANotFound() {
            when(employees.findWithReferencesById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(99L, request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    /** Recording an exit (FR-2.5), which is the only route to INACTIVE. */
    @Nested
    @DisplayName("deactivating an employee")
    class Deactivating {

        private final DeactivateEmployeeRequest request =
                new DeactivateEmployeeRequest(LocalDate.of(2026, 8, 31));

        @Test
        void recordsTheExitDateAndTheStatus() {
            Employee existing = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(existing));

            EmployeeSummaryResponse deactivated = service.deactivate(1L, request);

            assertThat(deactivated.status()).isEqualTo(EmployeeStatus.INACTIVE);
            assertThat(deactivated.exitDate()).isEqualTo(LocalDate.of(2026, 8, 31));
            verify(audit).record(eq(AuditEntityType.EMPLOYEE), eq(1L),
                    eq(AuditAction.EMPLOYEE_DEACTIVATED), anyMap());
        }

        @Test
        void neverDeletesTheRecord() {
            // ADR-014: a payslip from years ago must still resolve its employee.
            Employee existing = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(existing));

            service.deactivate(1L, request);

            verify(employees, never()).delete(any(Employee.class));
            verify(employees, never()).deleteById(any());
        }

        @Test
        void aSecondDeactivationIsRefusedRatherThanMovingTheExitDate() {
            // The exit date decides which periods the person is still paid for (FR-2.6),
            // so quietly overwriting it would be a payroll change disguised as a click.
            Employee alreadyLeft = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            alreadyLeft.deactivate(LocalDate.of(2026, 3, 31));
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(alreadyLeft));

            assertThatThrownBy(() -> service.deactivate(1L, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("2026-03-31");
            assertThat(alreadyLeft.getExitDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        void anExitBeforeTheJoiningDateIsRejected() {
            Employee existing = EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon");
            when(employees.findWithReferencesById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.deactivate(
                    1L, new DeactivateEmployeeRequest(LocalDate.of(2020, 1, 1))))
                    .isInstanceOf(ValidationException.class);
            assertThat(existing.isActive()).isTrue();
        }
    }
}
