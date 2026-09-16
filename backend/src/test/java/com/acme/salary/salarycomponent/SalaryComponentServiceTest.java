package com.acme.salary.salarycomponent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.salarycomponent.dto.CreateSalaryComponentRequest;
import com.acme.salary.salarycomponent.dto.SalaryComponentResponse;
import com.acme.salary.support.EmployeeFixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Component definition management: uniqueness, canonical codes, and the audit record. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalaryComponentServiceTest {

    @Mock
    private SalaryComponentRepository components;

    @Mock
    private AuditService audit;

    private SalaryComponentService service;

    @BeforeEach
    void setUp() {
        service = new SalaryComponentService(components, audit);
        when(components.save(any())).thenAnswer(invocation ->
                EmployeeFixtures.withId(invocation.getArgument(0), 42L));
    }

    private static CreateSalaryComponentRequest request(String code) {
        return new CreateSalaryComponentRequest(
                code, "House Rent Allowance", ComponentType.EARNING, CalculationType.FLAT,
                new BigDecimal("20000"), true);
    }

    @Test
    void createsAComponentAndReturnsIt() {
        SalaryComponentResponse response = service.create(request("HRA"));

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.code()).isEqualTo("HRA");
        assertThat(response.type()).isEqualTo(ComponentType.EARNING);
        assertThat(response.value()).isEqualByComparingTo("20000.00");
        assertThat(response.active()).isTrue();
    }

    @Test
    void checksUniquenessAgainstTheCanonicalCodeNotTheRawInput() {
        // 'hra' and 'HRA' must not become two components.
        ArgumentCaptor<String> checked = ArgumentCaptor.forClass(String.class);

        service.create(request("hra"));

        verify(components).existsByCode(checked.capture());
        assertThat(checked.getValue()).isEqualTo("HRA");
    }

    @Test
    void refusesADuplicateCodeWithA409() {
        when(components.existsByCode("HRA")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("HRA")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("HRA");
        verify(components, never()).save(any());
        verify(audit, never()).record(anyString(), any(), anyString(), anyMap());
    }

    @Test
    void rejectsAnInvalidDefinitionBeforeTouchingTheDatabase() {
        CreateSalaryComponentRequest overHundredPercent = new CreateSalaryComponentRequest(
                "PF", "Provident Fund", ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC,
                new BigDecimal("120"), false);

        assertThatThrownBy(() -> service.create(overHundredPercent))
                .isInstanceOf(ValidationException.class);
        verify(components, never()).existsByCode(anyString());
        verify(components, never()).save(any());
    }

    @Test
    void recordsTheCreationInTheAuditTrail() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);

        service.create(request("HRA"));

        verify(audit).record(
                org.mockito.ArgumentMatchers.eq(AuditEntityType.SALARY_COMPONENT),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(AuditAction.SALARY_COMPONENT_CREATED),
                details.capture());
        assertThat(details.getValue()).containsEntry("code", "HRA")
                .containsEntry("type", ComponentType.EARNING)
                .containsEntry("calculationType", CalculationType.FLAT);
    }

    @Test
    void listsActiveComponentsByDefault() {
        when(components.findAllByActiveTrueOrderByTypeAscCodeAsc())
                .thenReturn(List.of(component("BASIC"), component("HRA")));

        assertThat(service.list(false)).extracting(SalaryComponentResponse::code)
                .containsExactly("BASIC", "HRA");
        verify(components, never()).findAllByOrderByTypeAscCodeAsc();
    }

    @Test
    void listsRetiredComponentsOnlyWhenAskedTo() {
        // Retired definitions still appear on historical packages, so they must be
        // retrievable — just not offered for new ones.
        SalaryComponent retired = component("OLD_ALLOWANCE");
        retired.deactivate();
        when(components.findAllByOrderByTypeAscCodeAsc()).thenReturn(List.of(component("BASIC"), retired));

        List<SalaryComponentResponse> all = service.list(true);

        assertThat(all).extracting(SalaryComponentResponse::code).contains("OLD_ALLOWANCE");
        assertThat(all).filteredOn(response -> !response.active()).hasSize(1);
        verify(components, never()).findAllByActiveTrueOrderByTypeAscCodeAsc();
    }

    @Test
    void findsOneById() {
        when(components.findById(42L)).thenReturn(Optional.of(component("HRA")));

        assertThat(service.findById(42L).code()).isEqualTo("HRA");
    }

    @Test
    void findByIdOnAnUnknownComponentIsANotFound() {
        when(components.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Salary component 99");
    }

    private static SalaryComponent component(String code) {
        return new SalaryComponent(code, code, ComponentType.EARNING, CalculationType.FLAT,
                new BigDecimal("1000"), true);
    }
}
