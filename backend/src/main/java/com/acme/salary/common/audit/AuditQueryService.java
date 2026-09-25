package com.acme.salary.common.audit;

import com.acme.salary.common.audit.dto.AuditEventResponse;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.security.User;
import com.acme.salary.security.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the audit trail (FR-8.2).
 *
 * <p>Separate from {@link AuditService}, which writes. The write path joins somebody
 * else's transaction and must stay as small as possible; this one is an ADMIN read with
 * filters, paging and joins of its own, and putting both in one class would mean the
 * hottest write in the system carried a query service around with it.
 *
 * <p>There is no method here that writes, updates or deletes anything, and that is the
 * requirement rather than an omission: an audit trail that can be edited is not an audit
 * trail (ADR-013).
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    private final AuditEventRepository auditEvents;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public AuditQueryService(
            AuditEventRepository auditEvents, UserRepository users, ObjectMapper objectMapper) {
        this.auditEvents = auditEvents;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    /**
     * The trail, filtered and paged (FR-8.2).
     *
     * <p>Dates arrive as calendar days and are widened to a half-open instant range: from
     * the start of {@code from} to the start of the day after {@code to}. A caller asking
     * for "the 14th" means the whole of the 14th, and a range that ended at midnight on
     * the 14th would silently omit almost all of it.
     *
     * <p>UTC, because that is what the column stores and what the API returns everywhere
     * else. A day boundary is therefore a UTC day boundary — worth knowing when reading a
     * trail from a different timezone, and better than a server-local boundary nobody can
     * see.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> search(AuditSearch search, Pageable pageable) {
        Page<AuditEvent> page = auditEvents.search(
                search.actorUserId(),
                search.entityType(),
                search.entityId(),
                search.action(),
                startOfDay(search.from()),
                startOfDayAfter(search.to()),
                pageable);

        Map<Long, String> actors = actorEmails(page.getContent());

        return PageResponse.of(page, event -> AuditEventResponse.from(
                event,
                event.getActorUserId() == null ? null : actors.get(event.getActorUserId()),
                parseDetails(event)));
    }

    /**
     * Actor addresses for the page, in one query.
     *
     * <p>A join would be neater SQL and worse layering: the audit trail records an actor
     * id precisely so it does not depend on the account still existing. Resolving the
     * address separately keeps a deleted or renamed login from changing what the trail
     * says happened.
     */
    private Map<Long, String> actorEmails(List<AuditEvent> events) {
        List<Long> ids = events.stream()
                .map(AuditEvent::getActorUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> emails = new LinkedHashMap<>();
        for (User user : users.findAllById(ids)) {
            emails.put(user.getId(), user.getEmail());
        }
        return emails;
    }

    /**
     * The stored JSON, as JSON.
     *
     * <p>Unparseable text is logged and reported as absent rather than failing the page.
     * The column is written by this application and should always hold valid JSON, so a
     * row that does not is a bug worth a log line — but one bad row must not make the
     * whole trail unreadable, which is exactly when somebody is looking at it.
     */
    private JsonNode parseDetails(AuditEvent event) {
        String details = event.getDetails();
        if (details == null || details.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(details);
        } catch (JsonProcessingException e) {
            log.warn("Audit event {} has details that are not valid JSON", event.getId(), e);
            return null;
        }
    }

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Exclusive upper bound, so the last day of a range is included in full. */
    private static Instant startOfDayAfter(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
