package com.acme.salary.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import com.acme.salary.security.Role;
import com.acme.salary.support.ClockTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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

/**
 * What the audit trail records, and what it refuses to let fail.
 *
 * <p>Transaction joining ({@code Propagation.MANDATORY}) is a Spring concern and is not
 * asserted here; what these tests pin down is the content — the actor, the timestamp
 * source, and the handling of context that will not serialise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    @Mock
    private AuditEventRepository auditEvents;

    @Mock
    private CurrentUserProvider currentUser;

    private AuditService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ClockTestConfig.FIXED_INSTANT, ZoneOffset.UTC);
        service = new AuditService(auditEvents, currentUser, new ObjectMapper(), clock);
        when(currentUser.find()).thenReturn(
                Optional.of(new CurrentUser(99L, "hr@acme.test", Role.HR)));
    }

    private AuditEvent recorded() {
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEvents).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void recordsWhoWhatAndWhen() {
        service.record(AuditEntityType.SALARY_STRUCTURE, 500L, AuditAction.SALARY_STRUCTURE_ASSIGNED);

        AuditEvent event = recorded();
        assertThat(event.getActorUserId()).isEqualTo(99L);
        assertThat(event.getEntityType()).isEqualTo("SalaryStructure");
        assertThat(event.getEntityId()).isEqualTo(500L);
        assertThat(event.getAction()).isEqualTo("SALARY_STRUCTURE_ASSIGNED");
    }

    @Test
    void takesTheTimestampFromTheApplicationClock() {
        // Not Instant.now(): a test that cannot control "now" cannot assert on it.
        service.record(AuditEntityType.SALARY_COMPONENT, 1L, AuditAction.SALARY_COMPONENT_CREATED);

        assertThat(recorded().getOccurredAt()).isEqualTo(ClockTestConfig.FIXED_INSTANT);
    }

    @Test
    void serialisesContextAsJson() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeId", 7);
        details.put("annualCtc", "840000.00");

        service.record(AuditEntityType.SALARY_STRUCTURE, 500L, AuditAction.SALARY_STRUCTURE_ASSIGNED, details);

        assertThat(recorded().getDetails())
                .isEqualTo("{\"employeeId\":7,\"annualCtc\":\"840000.00\"}");
    }

    @Test
    void leavesDetailsNullRatherThanWritingAnEmptyObject() {
        service.record(AuditEntityType.EMPLOYEE, 1L, AuditAction.EMPLOYEE_CREATED, Map.of());

        assertThat(recorded().getDetails()).isNull();
    }

    @Test
    void recordsASystemActionWithNoActor() {
        // actor_user_id is nullable for exactly this case; the event still gets written.
        when(currentUser.find()).thenReturn(Optional.empty());

        service.record(AuditEntityType.PAYROLL_RUN, 3L, AuditAction.PAYROLL_RUN_CREATED);

        assertThat(recorded().getActorUserId()).isNull();
        assertThat(recorded().getAction()).isEqualTo("PAYROLL_RUN_CREATED");
    }

    @Test
    void dropsUnserialisableContextRatherThanLosingTheBusinessChange() {
        // Losing the context is bad; rolling back a salary revision because its audit
        // context would not serialise is worse.
        Map<String, Object> unserialisable = Map.of("offender", new Object());

        assertThatCode(() -> service.record(
                AuditEntityType.SALARY_STRUCTURE, 500L, AuditAction.SALARY_STRUCTURE_ASSIGNED, unserialisable))
                .doesNotThrowAnyException();

        AuditEvent event = recorded();
        assertThat(event.getDetails()).isNull();
        assertThat(event.getEntityId()).isEqualTo(500L);
    }

    @Test
    void treatsNullContextAsNoContext() {
        service.record(AuditEntityType.EMPLOYEE, 1L, AuditAction.EMPLOYEE_UPDATED, null);

        assertThat(recorded().getDetails()).isNull();
    }
}
