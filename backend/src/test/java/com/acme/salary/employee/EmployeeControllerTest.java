package com.acme.salary.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.common.error.GlobalExceptionHandler;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.common.web.PageableSanitizer;
import com.acme.salary.config.JacksonConfig;
import com.acme.salary.config.SecurityConfig;
import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import com.acme.salary.employee.dto.EmployeeSummaryResponse;
import com.acme.salary.security.RestAccessDeniedHandler;
import com.acme.salary.security.RestAuthenticationEntryPoint;
import com.acme.salary.support.EmployeeFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The list endpoint's HTTP contract and its authorisation, exercised with a mocked service
 * — so this runs with no database and no Docker.
 *
 * <p>The real {@link SecurityConfig} is imported rather than stubbed: the point is to
 * prove the deny-by-default chain and the role rules actually apply to this endpoint
 * (FR-1.3, FR-1.4).
 */
@WebMvcTest(controllers = EmployeeController.class)
@Import({SecurityConfig.class, JacksonConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class})
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployeeService employees;

    @Captor
    private ArgumentCaptor<EmployeeSearch> searchCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private static PageResponse<EmployeeSummaryResponse> onePage() {
        return new PageResponse<>(
                List.of(EmployeeSummaryResponse.from(
                        EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon"))),
                0, 20, 1, 1, false, false);
    }

    @Test
    @WithMockUser(roles = "HR")
    void returnsThePublishedPageShape() throws Exception {
        when(employees.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].employeeCode").value("E-001"))
                .andExpect(jsonPath("$.content[0].fullName").value("Asha Menon"))
                .andExpect(jsonPath("$.content[0].department.label").value("Engineering"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false));
    }

    @Test
    @WithMockUser(roles = "HR")
    void aListRowCarriesNoSalaryData() throws Exception {
        // NFR-2.7: compensation has no business in a list payload.
        when(employees.search(any(), any())).thenReturn(onePage());

        String body = mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("salary")
                .doesNotContainIgnoringCase("ctc")
                .doesNotContainIgnoringCase("gross");
    }

    @Test
    @WithMockUser(roles = "HR")
    void bindsEveryFilterOntoTheSearchCriteria() throws Exception {
        when(employees.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get("/api/v1/employees")
                        .param("q", "asha")
                        .param("departmentId", "10")
                        .param("designationId", "20")
                        .param("gradeId", "30")
                        .param("status", "ALL"))
                .andExpect(status().isOk());

        verify(employees).search(searchCaptor.capture(), any());
        EmployeeSearch search = searchCaptor.getValue();
        assertThat(search.nameQuery()).isEqualTo("asha");
        assertThat(search.departmentId()).isEqualTo(10L);
        assertThat(search.designationId()).isEqualTo(20L);
        assertThat(search.gradeId()).isEqualTo(30L);
        assertThat(search.statusFilter()).isEqualTo(StatusFilter.ALL);
    }

    @Test
    @WithMockUser(roles = "HR")
    void defaultsToActiveEmployeesAndAStableSort() throws Exception {
        when(employees.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get("/api/v1/employees")).andExpect(status().isOk());

        verify(employees).search(searchCaptor.capture(), pageableCaptor.capture());
        assertThat(searchCaptor.getValue().statusFilter()).isEqualTo(StatusFilter.ACTIVE_ONLY);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "employeeCode"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void capsAnOversizedPageRequest() throws Exception {
        when(employees.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get("/api/v1/employees").param("size", "5000")).andExpect(status().isOk());

        verify(employees).search(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(PageableSanitizer.MAX_PAGE_SIZE);
    }

    @Test
    @WithMockUser(roles = "HR")
    void translatesAReferenceSortKeyToItsEntityPath() throws Exception {
        when(employees.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get("/api/v1/employees").param("sort", "department,desc"))
                .andExpect(status().isOk());

        verify(employees).search(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "department.name"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAnUnknownSortKeyWithAFieldError() throws Exception {
        mockMvc.perform(get("/api/v1/employees").param("sort", "passwordHash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/v1/employees"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAnUnknownStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/employees").param("status", "RETIRED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @WithAnonymousUser
    void anUnauthenticatedCallerGets401NotAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeCannotListEveryone() throws Exception {
        // FR-1.5: an employee's own data is a different endpoint, not this one.
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayAlsoList() throws Exception {
        when(employees.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get("/api/v1/employees")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HR")
    void getByIdReturnsTheEmployee() throws Exception {
        when(employees.findById(1L)).thenReturn(
                EmployeeSummaryResponse.from(EmployeeFixtures.employee(1L, "E-001", "Asha", "Menon")));

        mockMvc.perform(get("/api/v1/employees/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value("E-001"));
    }
}
