package com.acme.salary.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import com.acme.salary.support.EmployeeFixtures;
import java.util.List;
import java.util.Optional;
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
}
