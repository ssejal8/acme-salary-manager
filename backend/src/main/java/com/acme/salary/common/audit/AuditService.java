package com.acme.salary.common.audit;

import com.acme.salary.security.CurrentUserProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records business actions in the audit trail.
 *
 * <p>Calls join the caller's transaction ({@link Propagation#MANDATORY}) rather than
 * opening their own. That is the whole design: an audit row cannot survive a rolled-back
 * change, and a committed change cannot escape its audit row (ADR-013). Requiring an
 * existing transaction also makes an accidental call from outside a business operation
 * fail loudly instead of writing a detached record.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEvents;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(
            AuditEventRepository auditEvents,
            CurrentUserProvider currentUser,
            ObjectMapper objectMapper,
            Clock clock) {
        this.auditEvents = auditEvents;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, String action) {
        record(entityType, entityId, action, Map.of());
    }

    /**
     * @param details small, non-sensitive context — figures and reasons, not whole records
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, String action, Map<String, ?> details) {
        Long actorId = currentUser.find().map(actor -> actor.id()).orElse(null);
        auditEvents.save(new AuditEvent(
                actorId, entityType, entityId, action, toJson(details), clock.instant()));
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            // Losing the context is bad; losing the business change because its context
            // would not serialise is worse.
            log.warn("Audit details could not be serialised and were dropped", e);
            return null;
        }
    }
}
