package com.acme.salary.salarystructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.salarycomponent.CalculationType;
import com.acme.salary.salarycomponent.ComponentType;
import com.acme.salary.salarystructure.dto.SalaryStructureComponentResponse;
import com.acme.salary.salarystructure.dto.SalaryStructureResponse;
import com.acme.salary.salarystructure.dto.StructureTotalsResponse;
import com.acme.salary.support.ApiSecurityTestConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract and authorisation for compensation packages. No database involved. */
@WebMvcTest(controllers = SalaryStructureController.class)
@Import(ApiSecurityTestConfig.class)
class SalaryStructureControllerTest {

    private static final String VALID_BODY = """
            {
              "effectiveFrom": "2026-04-01",
              "components": [
                {"componentId": 1, "value": "50000.00"},
                {"componentId": 2, "value": "20000.00"},
                {"componentId": 3, "value": "12"}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SalaryStructureService structures;

    private static SalaryStructureResponse response() {
        return new SalaryStructureResponse(
                500L, 7L, LocalDate.of(2026, 4, 1), null, true, null, Instant.parse("2026-09-16T09:00:00Z"),
                List.of(new SalaryStructureComponentResponse(1L, "BASIC", "Basic Salary",
                        ComponentType.EARNING, CalculationType.FLAT,
                        new BigDecimal("50000.00"), new BigDecimal("50000.00"))),
                List.of(new SalaryStructureComponentResponse(3L, "PF", "Provident Fund",
                        ComponentType.DEDUCTION, CalculationType.PERCENT_OF_BASIC,
                        new BigDecimal("12.00"), new BigDecimal("6000.00"))),
                totals());
    }

    private static StructureTotalsResponse totals() {
        return new StructureTotalsResponse(
                new BigDecimal("50000.00"), new BigDecimal("70000.00"),
                new BigDecimal("6000.00"), new BigDecimal("64000.00"), new BigDecimal("840000.00"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void assigningReturns201AndTheNewRevision() throws Exception {
        when(structures.assign(eq(7L), any())).thenReturn(response());

        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/employees/7/salary-structures"))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.totals.netMonthly").value("64000.00"))
                .andExpect(jsonPath("$.totals.annualCtc").value("840000.00"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void amountsAreStringsNotJsonNumbers() throws Exception {
        // ADR-006: a JSON number would invite the client to parse it into a double.
        when(structures.assign(eq(7L), any())).thenReturn(response());

        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.grossMonthly").value("70000.00"))
                .andExpect(jsonPath("$.earnings[0].monthlyAmount").value("50000.00"))
                .andExpect(jsonPath("$.deductions[0].configuredValue").value("12.00"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void previewComputesWithoutAssigning() throws Exception {
        when(structures.preview(eq(7L), any())).thenReturn(totals());

        mockMvc.perform(post("/api/v1/employees/7/salary-structures/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualCtc").value("840000.00"));

        verify(structures, never()).assign(any(), any());
    }

    @Test
    @WithMockUser(roles = "HR")
    void historyIsNewestFirst() throws Exception {
        when(structures.history(7L)).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/v1/employees/7/salary-structures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(500))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-04-01"));
    }

    @Test
    @WithMockUser(roles = "HR")
    void currentIs204WhenNoPackageHasBeenAssigned() throws Exception {
        when(structures.current(7L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/employees/7/salary-structures/current"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "HR")
    void aRuleViolationBecomesA400WithFieldErrors() throws Exception {
        when(structures.assign(eq(7L), any()))
                .thenThrow(ValidationException.field("overrideReason", "annual CTC falls outside grade G2's band"));

        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("overrideReason"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(
                        org.hamcrest.Matchers.containsString("outside grade")));
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAPackageWithNoComponents() throws Exception {
        String empty = """
                {"effectiveFrom": "2026-04-01", "components": []}
                """;

        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("components"));
        verify(structures, never()).assign(any(), any());
    }

    @Test
    @WithMockUser(roles = "HR")
    void rejectsAMissingEffectiveDate() throws Exception {
        String noDate = """
                {"components": [{"componentId": 1, "value": "50000.00"}]}
                """;

        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(noDate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("effectiveFrom"));
    }

    @Test
    @WithAnonymousUser
    void anUnauthenticatedCallerCannotSeeCompensation() throws Exception {
        mockMvc.perform(get("/api/v1/employees/7/salary-structures"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeCannotReadCompensationThroughThisEndpoint() throws Exception {
        // NFR-2.7 and FR-1.5: self-service access needs an ownership check, which is a
        // different endpoint, not a looser role rule on this one.
        mockMvc.perform(get("/api/v1/employees/7/salary-structures"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void anEmployeeCannotAssignCompensation() throws Exception {
        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verify(structures, never()).assign(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayAssignCompensation() throws Exception {
        when(structures.assign(eq(7L), any())).thenReturn(response());

        mockMvc.perform(post("/api/v1/employees/7/salary-structures")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }
}
