package com.acme.salary.orgdata;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.orgdata.dto.DepartmentResponse;
import com.acme.salary.orgdata.dto.DesignationResponse;
import com.acme.salary.orgdata.dto.GradeResponse;
import com.acme.salary.support.ApiSecurityTestConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract and authorisation for the three reference-data lists. No database. */
@WebMvcTest(controllers = ReferenceDataController.class)
@Import(ApiSecurityTestConfig.class)
class ReferenceDataControllerTest {

    private static final String[] ALL_PATHS = {
            "/api/v1/departments", "/api/v1/designations", "/api/v1/grades"
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReferenceDataService referenceData;

    @Test
    @WithMockUser(roles = "HR")
    void departmentsCarryTheirCodeAndName() throws Exception {
        when(referenceData.departments()).thenReturn(List.of(
                new DepartmentResponse(1L, "ENG", "Engineering"),
                new DepartmentResponse(2L, "FIN", "Finance")));

        mockMvc.perform(get("/api/v1/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].code").value("ENG"))
                .andExpect(jsonPath("$[0].name").value("Engineering"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void aDepartmentDoesNotExposeItsHeadEmployee() throws Exception {
        // Reference data does not point back at employees (architecture §4.2).
        when(referenceData.departments()).thenReturn(List.of(new DepartmentResponse(1L, "ENG", "Engineering")));

        mockMvc.perform(get("/api/v1/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].headEmployeeId").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "HR")
    void designationsCarryTheirTitle() throws Exception {
        when(referenceData.designations()).thenReturn(List.of(
                new DesignationResponse(20L, "Software Engineer")));

        mockMvc.perform(get("/api/v1/designations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(20))
                .andExpect(jsonPath("$[0].title").value("Software Engineer"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void gradesCarryTheirCtcBandAsStrings() throws Exception {
        // ADR-006: money is a string at two decimal places, never a JSON number.
        when(referenceData.grades()).thenReturn(List.of(
                new GradeResponse(30L, "G2", new BigDecimal("800000.00"), new BigDecimal("1500000.00"))));

        mockMvc.perform(get("/api/v1/grades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("G2"))
                .andExpect(jsonPath("$[0].minCtc").value("800000.00"))
                .andExpect(jsonPath("$[0].maxCtc").value("1500000.00"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void anUnboundedSideOfABandIsAbsentRatherThanNull() throws Exception {
        // Jackson is configured for non_null inclusion, and an absent bound never
        // rejects a package (FR-4.3) — so a client must read "missing" as "unbounded".
        when(referenceData.grades()).thenReturn(List.of(
                new GradeResponse(40L, "G4", new BigDecimal("2500000.00"), null)));

        mockMvc.perform(get("/api/v1/grades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].minCtc").value("2500000.00"))
                .andExpect(jsonPath("$[0].maxCtc").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayReadReferenceDataToo() throws Exception {
        when(referenceData.grades()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/grades")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/departments", "/api/v1/designations", "/api/v1/grades"})
    @WithAnonymousUser
    void anUnauthenticatedCallerLearnsNothingAboutTheOrganisationsShape(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/departments", "/api/v1/designations", "/api/v1/grades"})
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeCannotReadReferenceData(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser(roles = "HR")
    void anEmptyListIsAnEmptyArrayNotA204() throws Exception {
        // A dropdown with nothing in it is still a successful read.
        for (String path : ALL_PATHS) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }
}
