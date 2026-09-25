package com.acme.salary.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.salary.common.audit.dto.AuditEventResponse;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.security.Role;
import com.acme.salary.security.User;
import com.acme.salary.security.UserRepository;
import com.acme.salary.support.EmployeeFixtures;
import com.acme.salary.support.UserFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Reading the audit trail (FR-8.2).
 *
 * <p>The cases that matter are the date range, which is the one place a caller's meaning
 * and the column's meaning differ, and the actor lookup, which must not become a query per
 * row on a table that only ever grows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditQueryServiceTest {

    @Mock
    private AuditEventRepository auditEvents;

    @Mock
    private UserRepository users;

    private AuditQueryService service;

    private final Pageable pageable = PageRequest.of(0, 25);

    @Captor
    private ArgumentCaptor<Instant> fromCaptor;

    @Captor
    private ArgumentCaptor<Instant> toCaptor;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(auditEvents, users, new ObjectMapper());
    }

    private static AuditEvent event(Long actorUserId, String details) {
        return EmployeeFixtures.withId(new AuditEvent(
                actorUserId,
                AuditEntityType.PAYROLL_RUN,
                7L,
                AuditAction.PAYROLL_RUN_FINALISED,
                details,
                Instant.parse("2026-09-14T10:00:00Z")), 1L);
    }

    private void repositoryReturns(AuditEvent... events) {
        when(auditEvents.search(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(events), pageable, events.length));
    }

    @Test
    void returnsWhatWasRecorded() {
        repositoryReturns(event(2L, "{\"period\":\"2026-08\",\"totalNet\":\"883288000.00\"}"));
        User hr = UserFixtures.user(2L, "hr@acme.test", Role.HR, "$2a$10$hash");
        when(users.findAllById(List.of(2L))).thenReturn(List.of(hr));

        PageResponse<AuditEventResponse> found =
                service.search(new AuditSearch(null, null, null, null, null, null), pageable);

        assertThat(found.content()).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo("PAYROLL_RUN_FINALISED");
            assertThat(row.entityType()).isEqualTo("PayrollRun");
            assertThat(row.entityId()).isEqualTo(7L);
            assertThat(row.actorUserId()).isEqualTo(2L);
            assertThat(row.actorEmail()).isEqualTo("hr@acme.test");
            assertThat(row.occurredAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
        });
    }

    @Test
    void passesTheDetailsThroughAsJsonRatherThanAsAStringOfJson() {
        // A client that has to parse a string to read a figure is being handed the
        // database's storage format instead of an API.
        repositoryReturns(event(2L, "{\"totalNet\":\"883288000.00\",\"employeeCount\":9959}"));

        var details = service.search(new AuditSearch(null, null, null, null, null, null), pageable)
                .content().get(0).details();

        assertThat(details).isNotNull();
        assertThat(details.get("totalNet").asText()).isEqualTo("883288000.00");
        assertThat(details.get("employeeCount").asInt()).isEqualTo(9959);
    }

    @Test
    void reportsUnreadableDetailsAsAbsentRatherThanFailingThePage() {
        // One bad row must not make the whole trail unreadable, which is exactly when
        // somebody is looking at it.
        repositoryReturns(event(2L, "not json at all"));

        assertThat(service.search(new AuditSearch(null, null, null, null, null, null), pageable)
                .content().get(0).details()).isNull();
    }

    @Test
    void handlesASystemActionWithNoActor() {
        repositoryReturns(event(null, null));

        AuditEventResponse row = service
                .search(new AuditSearch(null, null, null, null, null, null), pageable)
                .content().get(0);

        assertThat(row.actorUserId()).isNull();
        assertThat(row.actorEmail()).isNull();
        verify(users, never()).findAllById(any());
    }

    @Test
    void reportsAnActorWhoseLoginNoLongerExistsByIdAlone() {
        // The trail outlives the accounts in it, which is why the id is recorded as well
        // as the address.
        repositoryReturns(event(99L, null));
        when(users.findAllById(List.of(99L))).thenReturn(List.of());

        AuditEventResponse row = service
                .search(new AuditSearch(null, null, null, null, null, null), pageable)
                .content().get(0);

        assertThat(row.actorUserId()).isEqualTo(99L);
        assertThat(row.actorEmail()).isNull();
    }

    @Test
    void resolvesActorsOnceForThePageRatherThanPerRow() {
        // This table only grows, so an N+1 here gets slower every day.
        repositoryReturns(event(2L, null), event(2L, null), event(3L, null));
        when(users.findAllById(any())).thenReturn(List.of());

        service.search(new AuditSearch(null, null, null, null, null, null), pageable);

        verify(users, times(1)).findAllById(any());
    }

    @Nested
    @DisplayName("the date range")
    class DateRange {

        @Test
        void coversTheWholeOfTheLastDayAsked() {
            // "The 14th to the 14th" means all of the 14th. A range ending at midnight on
            // the 14th would omit almost all of it.
            repositoryReturns();

            service.search(
                    new AuditSearch(null, null, null, null,
                            LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14)),
                    pageable);

            verify(auditEvents).search(any(), any(), any(), any(),
                    fromCaptor.capture(), toCaptor.capture(), any());
            assertThat(fromCaptor.getValue()).isEqualTo(Instant.parse("2026-09-14T00:00:00Z"));
            // Half-open: the start of the next day, so no row is counted twice by two
            // adjacent queries.
            assertThat(toCaptor.getValue()).isEqualTo(Instant.parse("2026-09-15T00:00:00Z"));
        }

        @Test
        void leavesAnAbsentBoundUnbounded() {
            repositoryReturns();

            service.search(
                    new AuditSearch(null, null, null, null, LocalDate.of(2026, 9, 14), null),
                    pageable);

            verify(auditEvents).search(any(), any(), any(), any(),
                    fromCaptor.capture(), toCaptor.capture(), any());
            assertThat(fromCaptor.getValue()).isNotNull();
            assertThat(toCaptor.getValue()).isNull();
        }
    }

    @Test
    void passesEveryFilterAndThePageRequestToTheDatabase() {
        // Filtering and paging must not happen after loading: this table has no ceiling.
        repositoryReturns();
        Pageable requested = PageRequest.of(2, 50);

        service.search(
                new AuditSearch(2L, AuditEntityType.EMPLOYEE, 1001L,
                        AuditAction.EMPLOYEE_UPDATED, null, null),
                requested);

        verify(auditEvents).search(
                2L, "Employee", 1001L, "EMPLOYEE_UPDATED", null, null, requested);
    }
}
