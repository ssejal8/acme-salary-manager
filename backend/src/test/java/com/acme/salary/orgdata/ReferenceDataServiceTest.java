package com.acme.salary.orgdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.orgdata.dto.CreateDepartmentRequest;
import com.acme.salary.orgdata.dto.SaveDesignationRequest;
import com.acme.salary.orgdata.dto.SaveGradeRequest;
import com.acme.salary.orgdata.dto.UpdateDepartmentRequest;
import com.acme.salary.support.EmployeeFixtures;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Reference-data writes (FR-3.1 to FR-3.3).
 *
 * <p>The interesting cases are the duplicates, because these three lists are what every
 * dropdown in the application is built from: two departments that read identically would
 * be indistinguishable to whoever has to pick one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferenceDataServiceTest {

    @Mock
    private DepartmentRepository departments;

    @Mock
    private DesignationRepository designations;

    @Mock
    private GradeRepository grades;

    @InjectMocks
    private ReferenceDataService service;

    @Nested
    @DisplayName("departments")
    class Departments {

        @Test
        void addsOneWithTheCodeUppercased() {
            when(departments.save(any(Department.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 5L));

            var created = service.createDepartment(new CreateDepartmentRequest(" ops ", "Operations"));

            assertThat(created.id()).isEqualTo(5L);
            assertThat(created.code()).isEqualTo("OPS");
            assertThat(created.name()).isEqualTo("Operations");
        }

        @Test
        void checksForADuplicateAgainstTheNormalisedCode() {
            // The bug this guards: "eng" past a lookup for "ENG" would create a second
            // Engineering that reads identically in every dropdown.
            when(departments.existsByCode("ENG")).thenReturn(true);

            assertThatThrownBy(() -> service.createDepartment(
                    new CreateDepartmentRequest("eng", "Engineering")))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(thrown -> assertThat(((ConflictException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("code")));

            verify(departments, never()).save(any(Department.class));
        }

        @Test
        void renamesOneWithoutTouchingItsCode() {
            // FR-3.1: the code is what reports and saved filters refer to.
            Department engineering = EmployeeFixtures.department(1L, "ENG", "Engineering");
            when(departments.findById(1L)).thenReturn(Optional.of(engineering));

            var renamed = service.renameDepartment(1L, new UpdateDepartmentRequest("Engineering & QA"));

            assertThat(renamed.name()).isEqualTo("Engineering & QA");
            assertThat(renamed.code()).isEqualTo("ENG");
        }

        @Test
        void renamingAnUnknownDepartmentIsANotFound() {
            when(departments.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.renameDepartment(99L, new UpdateDepartmentRequest("X")))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("designations")
    class Designations {

        @Test
        void addsOne() {
            when(designations.save(any(Designation.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 8L));

            var created = service.createDesignation(new SaveDesignationRequest(" QA Engineer "));

            assertThat(created.id()).isEqualTo(8L);
            assertThat(created.title()).isEqualTo("QA Engineer");
        }

        @Test
        void refusesADuplicateTitleWhateverItsCase() {
            when(designations.existsByTitleIgnoreCase("software engineer")).thenReturn(true);

            assertThatThrownBy(() -> service.createDesignation(
                    new SaveDesignationRequest("software engineer")))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void retitlesOne() {
            Designation engineer = EmployeeFixtures.designation(2L, "Software Engineer");
            when(designations.findById(2L)).thenReturn(Optional.of(engineer));

            assertThat(service.retitleDesignation(2L, new SaveDesignationRequest("Engineer")).title())
                    .isEqualTo("Engineer");
        }

        @Test
        void keepingItsOwnTitleIsNotAConflictWithItself() {
            // The reason the check excludes this row: saving an unchanged form must work.
            Designation engineer = EmployeeFixtures.designation(2L, "Software Engineer");
            when(designations.findById(2L)).thenReturn(Optional.of(engineer));
            when(designations.existsByTitleIgnoreCaseAndIdNot("Software Engineer", 2L))
                    .thenReturn(false);

            assertThat(service.retitleDesignation(
                    2L, new SaveDesignationRequest("Software Engineer")).title())
                    .isEqualTo("Software Engineer");
        }

        @Test
        void refusesATitleAnotherDesignationAlreadyUses() {
            Designation engineer = EmployeeFixtures.designation(2L, "Software Engineer");
            when(designations.findById(2L)).thenReturn(Optional.of(engineer));
            when(designations.existsByTitleIgnoreCaseAndIdNot("Finance Analyst", 2L))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.retitleDesignation(
                    2L, new SaveDesignationRequest("Finance Analyst")))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("grades and their CTC bands")
    class Grades {

        @Test
        void addsOneWithItsBand() {
            when(grades.save(any(Grade.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 9L));

            var created = service.createGrade(new SaveGradeRequest(
                    "G5", new BigDecimal("4000000"), new BigDecimal("6000000")));

            assertThat(created.name()).isEqualTo("G5");
            assertThat(created.minCtc()).isEqualByComparingTo("4000000.00");
            assertThat(created.maxCtc()).isEqualByComparingTo("6000000.00");
        }

        @Test
        void acceptsAnAbsentBoundAsUnboundedRatherThanZero() {
            // FR-4.3: a top grade with no ceiling never rejects a package.
            when(grades.save(any(Grade.class)))
                    .thenAnswer(call -> EmployeeFixtures.withId(call.getArgument(0), 9L));

            var created = service.createGrade(
                    new SaveGradeRequest("G6", new BigDecimal("6000000"), null));

            assertThat(created.maxCtc()).isNull();
        }

        @Test
        void refusesABandWhoseMaximumIsBelowItsMinimum() {
            // Owned by the entity, which is where the invariant lives — and by
            // ck_grades_ctc, so data that bypassed the API cannot hold one either.
            assertThatThrownBy(() -> service.createGrade(new SaveGradeRequest(
                    "G7", new BigDecimal("6000000"), new BigDecimal("1000000"))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(error -> assertThat(error.field()).isEqualTo("maxCtc")));

            verify(grades, never()).save(any(Grade.class));
        }

        @Test
        void refusesADuplicateName() {
            when(grades.existsByNameIgnoreCase("G2")).thenReturn(true);

            assertThatThrownBy(() -> service.createGrade(new SaveGradeRequest("G2", null, null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void amendsTheNameAndTheBandTogether() {
            Grade grade = EmployeeFixtures.grade(3L, "G2");
            when(grades.findById(3L)).thenReturn(Optional.of(grade));

            var updated = service.updateGrade(3L, new SaveGradeRequest(
                    "G2 (revised)", new BigDecimal("900000"), new BigDecimal("1600000")));

            assertThat(updated.name()).isEqualTo("G2 (revised)");
            assertThat(updated.minCtc()).isEqualByComparingTo("900000.00");
            assertThat(updated.maxCtc()).isEqualByComparingTo("1600000.00");
        }

        @Test
        void amendingAnUnknownGradeIsANotFound() {
            when(grades.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateGrade(99L, new SaveGradeRequest("X", null, null)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    void offersNoWayToDeleteAnyOfIt() {
        // Employees reference these rows with ON DELETE RESTRICT, and removing one would
        // orphan the history that names it. Asserted so a future "tidy-up" endpoint has
        // to argue with a test first.
        assertThat(ReferenceDataService.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("delete")
                        || method.getName().toLowerCase().contains("remove"));
    }
}
