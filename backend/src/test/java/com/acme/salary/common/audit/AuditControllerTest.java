package com.acme.salary.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.salary.common.audit.dto.AuditEventResponse;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.support.ApiSecurityTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
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
 * The audit endpoint's contract and, mostly, who may reach it.
 *
 * <p>This is the strictest authorisation in the API — ADMIN alone — because one page of
 * this trail spans every feature: which salaries changed, who changed them and what the
 * figures were. HR's access to employee data deliberately does not extend to the record of
 * everybody's actions, including their own.
 */
@WebMvcTest(controllers = AuditController.class)
@Import(ApiSecurityTestConfig.class)
class AuditControllerTest {

    private static final String AUDIT = "/api/v1/audit-events";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditQueryService auditEvents;

    @Captor
    private ArgumentCaptor<AuditSearch> searchCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private static PageResponse<AuditEventResponse> onePage() {
        var details = new ObjectMapper().createObjectNode().put("employeeCode", "E-1001");
        return new PageResponse<>(
                List.of(new AuditEventResponse(
                        1L,
                        Instant.parse("2026-09-14T10:00:00Z"),
                        2L,
                        "hr@acme.test",
                        AuditEntityType.EMPLOYEE,
                        1001L,
                        AuditAction.EMPLOYEE_DEACTIVATED,
                        details)),
                0, 25, 1, 1, false, false);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsTheTrailInThePublishedPageShape() throws Exception {
        when(auditEvents.search(any(), any())).thenReturn(onePage());

        mockMvc.perform(get(AUDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("EMPLOYEE_DEACTIVATED"))
                .andExpect(jsonPath("$.content[0].entityType").value("Employee"))
                .andExpect(jsonPath("$.content[0].entityId").value(1001))
                .andExpect(jsonPath("$.content[0].actorEmail").value("hr@acme.test"))
                // Details are JSON, not a string of JSON: the client reads a field.
                .andExpect(jsonPath("$.content[0].details.employeeCode").value("E-1001"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void bindsEveryFilterOntoTheSearch() throws Exception {
        when(auditEvents.search(searchCaptor.capture(), any())).thenReturn(onePage());

        mockMvc.perform(get(AUDIT)
                        .param("actorUserId", "2")
                        .param("entityType", "PayrollRun")
                        .param("entityId", "7")
                        .param("action", "PAYROLL_RUN_FINALISED")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-30"))
                .andExpect(status().isOk());

        AuditSearch search = searchCaptor.getValue();
        assertThat(search.actorUserId()).isEqualTo(2L);
        assertThat(search.entityType()).isEqualTo("PayrollRun");
        assertThat(search.entityId()).isEqualTo(7L);
        assertThat(search.action()).isEqualTo("PAYROLL_RUN_FINALISED");
        assertThat(search.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(search.to()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void defaultsToNewestFirstWithATiebreaker() throws Exception {
        // Two events in one transaction share a timestamp, so it is not a total order —
        // and paging without one can repeat or skip rows.
        when(auditEvents.search(any(), pageableCaptor.capture())).thenReturn(onePage());

        mockMvc.perform(get(AUDIT)).andExpect(status().isOk());

        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsAnUnlistedSortKey() throws Exception {
        mockMvc.perform(get(AUDIT).param("sort", "details,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsAMalformedDate() throws Exception {
        mockMvc.perform(get(AUDIT).param("from", "last-tuesday"))
                .andExpect(status().isBadRequest());

        verify(auditEvents, never()).search(any(), any());
    }

    @Test
    @WithMockUser(roles = "HR")
    void isRefusedToHr() throws Exception {
        // The one read HR does not get. Their access to employee data does not extend to
        // the record of who did what — including their own actions.
        mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());

        verify(auditEvents, never()).search(any(), any());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void isRefusedToAnEmployee() throws Exception {
        mockMvc.perform(get(AUDIT)).andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void needsAToken() throws Exception {
        mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
    }
}
