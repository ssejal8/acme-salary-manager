package com.acme.salary.salarystructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.employee.EmployeeCompensationContext;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.employee.EmployeeStatus;
import com.acme.salary.orgdata.CtcBand;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarycomponent.SalaryComponent;
import com.acme.salary.salarycomponent.SalaryComponentRepository;
import com.acme.salary.salarystructure.dto.AssignSalaryStructureRequest;
import com.acme.salary.salarystructure.dto.AssignSalaryStructureRequest.ComponentAssignment;
import com.acme.salary.salarystructure.dto.SalaryStructureResponse;
import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import com.acme.salary.security.Role;
import com.acme.salary.support.EmployeeFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
 * The rules that need more than one fact: effective dating against the employee, the
 * package against the grade band, and superseding the previous revision. Mocked
 * repositories, so no database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalaryStructureServiceTest {

    private static final Long EMPLOYEE_ID = 7L;
    private static final LocalDate JOINED = LocalDate.of(2024, 4, 1);

    @Mock
    private SalaryStructureRepository structures;

    @Mock
    private SalaryComponentRepository components;

    @Mock
    private EmployeeService employees;

    @Mock
    private CurrentUserProvider currentUser;

    @Mock
    private AuditService audit;

    private SalaryStructureService service;

    private SalaryComponent basic;
    private SalaryComponent hra;
    private SalaryComponent providentFund;

    @BeforeEach
    void setUp() {
        service = new SalaryStructureService(structures, components, employees, currentUser, audit);

        basic = component(1L, "BASIC", ComponentType.EARNING, CalculationType.FLAT);
        hra = component(2L, "HRA", ComponentType.EARNING, CalculationType.FLAT);
        providentFund = component(3L, "PF", ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC);

        when(components.findAllByIdIn(any())).thenReturn(List.of(basic, hra, providentFund));
        when(employees.compensationContext(EMPLOYEE_ID)).thenReturn(context(
                EmployeeStatus.ACTIVE, new CtcBand(new BigDecimal("800000"), new BigDecimal("1500000"))));
        when(currentUser.require()).thenReturn(new CurrentUser(99L, "hr@acme.test", Role.HR));
        when(structures.findCurrent(EMPLOYEE_ID)).thenReturn(Optional.empty());
        when(structures.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SalaryComponent component(
            long id, String code, ComponentType type, CalculationType calculationType) {
        SalaryComponent component = new SalaryComponent(
                code, code, type, calculationType,
                calculationType == CalculationType.PERCENT_OF_BASIC
                        ? new BigDecimal("12")
                        : BigDecimal.ZERO,
                true);
        return EmployeeFixtures.withId(component, id);
    }

    private static EmployeeCompensationContext context(EmployeeStatus status, CtcBand band) {
        return new EmployeeCompensationContext(
                EMPLOYEE_ID, "E-001", "Asha Menon", JOINED,
                status == EmployeeStatus.INACTIVE ? LocalDate.of(2026, 1, 31) : null,
                status, 10L, "Engineering", 30L, "G2", band);
    }

    /** Basic 50,000 + HRA 20,000 − PF 12% → gross 70,000, net 64,000, CTC 840,000. */
    private static AssignSalaryStructureRequest request(LocalDate effectiveFrom, String overrideReason) {
        return new AssignSalaryStructureRequest(
                effectiveFrom,
                List.of(
                        new ComponentAssignment(1L, new BigDecimal("50000")),
                        new ComponentAssignment(2L, new BigDecimal("20000")),
                        new ComponentAssignment(3L, new BigDecimal("12"))),
                overrideReason);
    }

    @Nested
    @DisplayName("assignment")
    class Assignment {

        @Test
        void savesTheRevisionWithItsComponentsAndTotals() {
            SalaryStructureResponse response =
                    service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null));

            assertThat(response.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(response.current()).isTrue();
            assertThat(response.totals().grossMonthly()).isEqualByComparingTo("70000.00");
            assertThat(response.totals().netMonthly()).isEqualByComparingTo("64000.00");
            assertThat(response.totals().annualCtc()).isEqualByComparingTo("840000.00");
            assertThat(response.earnings()).extracting("code").containsExactly("BASIC", "HRA");
            assertThat(response.deductions()).extracting("code").containsExactly("PF");
        }

        @Test
        void attributesTheRevisionToTheActingUser() {
            // salary_structures.created_by is NOT NULL: a raise must always have an author.
            ArgumentCaptor<SalaryStructure> saved = ArgumentCaptor.forClass(SalaryStructure.class);

            service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null));

            verify(structures).save(saved.capture());
            assertThat(saved.getValue().getCreatedBy()).isEqualTo(99L);
        }

        @Test
        void supersedesTheCurrentRevisionRatherThanEditingIt() {
            SalaryStructure existing = new SalaryStructure(EMPLOYEE_ID, JOINED, 99L, null);
            when(structures.findCurrent(EMPLOYEE_ID)).thenReturn(Optional.of(existing));

            service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null));

            assertThat(existing.getSupersededOn()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(existing.isCurrent()).isFalse();
            verify(structures).save(any(SalaryStructure.class));
        }

        @Test
        void recordsBothTheAssignmentAndTheSupersession() {
            when(structures.findCurrent(EMPLOYEE_ID))
                    .thenReturn(Optional.of(EmployeeFixtures.withId(
                            new SalaryStructure(EMPLOYEE_ID, JOINED, 99L, null), 500L)));

            service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null));

            verify(audit).record(anyString(), eq(500L), eq(AuditAction.SALARY_STRUCTURE_SUPERSEDED), anyMap());
            verify(audit).record(anyString(), any(), eq(AuditAction.SALARY_STRUCTURE_ASSIGNED), anyMap());
        }

        @Test
        void refusesARevisionEffectiveOnADateAlreadyTaken() {
            when(structures.existsByEmployeeIdAndEffectiveFrom(EMPLOYEE_ID, LocalDate.of(2026, 4, 1)))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("2026-04-01");
            verify(structures, never()).save(any());
        }

        @Test
        void refusesARevisionThatWouldNotPostdateTheCurrentOne() {
            // The superseded date must fall after the revision it closes; the database
            // enforces the same thing.
            SalaryStructure existing = new SalaryStructure(EMPLOYEE_ID, LocalDate.of(2026, 4, 1), 99L, null);
            when(structures.findCurrent(EMPLOYEE_ID)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesAnUnknownEmployee() {
            when(employees.compensationContext(404L)).thenThrow(NotFoundException.of("Employee", 404L));

            assertThatThrownBy(() -> service.assign(404L, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusesAnUnattributableRequest() {
            when(currentUser.require())
                    .thenThrow(new org.springframework.security.access.AccessDeniedException("no actor"));

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verify(structures, never()).save(any());
        }
    }

    @Nested
    @DisplayName("effective date")
    class EffectiveDate {

        @Test
        void mayNotPrecedeTheDateOfJoining() {
            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(JOINED.minusDays(1), null)))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(fieldError ->
                                    assertThat(fieldError.field()).isEqualTo("effectiveFrom")));
        }

        @Test
        void mayEqualTheDateOfJoining() {
            assertThatCode(() -> service.assign(EMPLOYEE_ID, request(JOINED, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        void isRefusedForALeaver() {
            when(employees.compensationContext(EMPLOYEE_ID)).thenReturn(context(
                    EmployeeStatus.INACTIVE, CtcBand.UNBOUNDED));

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("grade band")
    class GradeBand {

        @Test
        void acceptsAPackageInsideTheBand() {
            // 840,000 CTC against an 800,000–1,500,000 band.
            assertThatCode(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesAPackageOutsideTheBandWithoutAReason() {
            when(employees.compensationContext(EMPLOYEE_ID)).thenReturn(context(
                    EmployeeStatus.ACTIVE, new CtcBand(new BigDecimal("2000000"), new BigDecimal("3000000"))));

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .singleElement()
                            .satisfies(fieldError -> {
                                assertThat(fieldError.field()).isEqualTo("overrideReason");
                                assertThat(fieldError.message()).contains("840000.00", "G2");
                            }));
        }

        @Test
        void allowsAnOutOfBandPackageWhenAReasonIsGiven() {
            when(employees.compensationContext(EMPLOYEE_ID)).thenReturn(context(
                    EmployeeStatus.ACTIVE, new CtcBand(new BigDecimal("2000000"), new BigDecimal("3000000"))));

            SalaryStructureResponse response = service.assign(
                    EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), "Retention case approved by CFO"));

            assertThat(response.overrideReason()).isEqualTo("Retention case approved by CFO");
        }

        @Test
        void neverRejectsWhenTheGradeHasNoBandConfigured() {
            when(employees.compensationContext(EMPLOYEE_ID))
                    .thenReturn(context(EmployeeStatus.ACTIVE, CtcBand.UNBOUNDED));

            assertThatCode(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("component resolution")
    class ComponentResolution {

        @Test
        void refusesAnUnknownComponent() {
            when(components.findAllByIdIn(any())).thenReturn(List.of(basic, hra));

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .anySatisfy(fieldError ->
                                    assertThat(fieldError.message()).contains("does not exist")));
        }

        @Test
        void refusesARetiredComponentForANewPackage() {
            providentFund.deactivate();

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null)))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                            .anySatisfy(fieldError ->
                                    assertThat(fieldError.message()).contains("no longer active")));
        }

        @Test
        void refusesTheSameComponentTwice() {
            AssignSalaryStructureRequest duplicated = new AssignSalaryStructureRequest(
                    LocalDate.of(2026, 4, 1),
                    List.of(
                            new ComponentAssignment(1L, new BigDecimal("50000")),
                            new ComponentAssignment(2L, new BigDecimal("20000")),
                            new ComponentAssignment(2L, new BigDecimal("10000"))),
                    null);

            assertThatThrownBy(() -> service.assign(EMPLOYEE_ID, duplicated))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        void computesTotalsWithoutSavingAnything() {
            var totals = service.preview(EMPLOYEE_ID, request(LocalDate.of(2026, 4, 1), null));

            assertThat(totals.grossMonthly()).isEqualByComparingTo("70000.00");
            assertThat(totals.netMonthly()).isEqualByComparingTo("64000.00");
            verify(structures, never()).save(any());
            verify(audit, never()).record(anyString(), anyLong(), anyString(), anyMap());
        }

        @Test
        void rejectsExactlyWhatTheAssignmentWouldReject() {
            // Otherwise the form would green-light a package the save then refuses.
            assertThatThrownBy(() -> service.preview(EMPLOYEE_ID, request(JOINED.minusDays(1), null)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        void returnsEveryRevisionNewestFirst() {
            SalaryStructure older = new SalaryStructure(EMPLOYEE_ID, JOINED, 99L, null);
            older.addComponent(basic, new BigDecimal("40000"));
            older.supersede(LocalDate.of(2026, 4, 1));
            SalaryStructure current = new SalaryStructure(EMPLOYEE_ID, LocalDate.of(2026, 4, 1), 99L, null);
            current.addComponent(basic, new BigDecimal("50000"));
            when(structures.findHistory(EMPLOYEE_ID)).thenReturn(List.of(current, older));

            List<SalaryStructureResponse> history = service.history(EMPLOYEE_ID);

            assertThat(history).hasSize(2);
            assertThat(history.get(0).current()).isTrue();
            assertThat(history.get(0).totals().grossMonthly()).isEqualByComparingTo("50000.00");
            // The superseded revision keeps its own figures — that is the point of ADR-009.
            assertThat(history.get(1).current()).isFalse();
            assertThat(history.get(1).totals().grossMonthly()).isEqualByComparingTo("40000.00");
            assertThat(history.get(1).supersededOn()).isEqualTo(LocalDate.of(2026, 4, 1));
        }

        @Test
        void isEmptyForAnEmployeeWithNoPackageYet() {
            when(structures.findHistory(EMPLOYEE_ID)).thenReturn(List.of());

            assertThat(service.history(EMPLOYEE_ID)).isEmpty();
        }

        @Test
        void currentIsEmptyRatherThanAnErrorWhenNonePackageExists() {
            assertThat(service.current(EMPLOYEE_ID)).isEmpty();
        }
    }
}
