package com.acme.salary.common.audit.dto;

import com.acme.salary.common.audit.AuditEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One row of the audit trail (FR-8.2).
 *
 * @param actorEmail the actor's login address, resolved for the page in one batch. Null
 *     for a system-initiated action, and also for an actor whose login no longer exists —
 *     the trail outlives the accounts in it, which is the point of keeping the id as well.
 * @param details the context recorded with the action, as JSON rather than as a string of
 *     JSON: a client that has to parse a string to read {@code totalNet} is being handed
 *     the database's storage format instead of an API. Null when the action recorded none.
 */
public record AuditEventResponse(
        Long id,
        Instant occurredAt,
        Long actorUserId,
        String actorEmail,
        String entityType,
        Long entityId,
        String action,
        JsonNode details) {

    public static AuditEventResponse from(AuditEvent event, String actorEmail, JsonNode details) {
        return new AuditEventResponse(
                event.getId(),
                event.getOccurredAt(),
                event.getActorUserId(),
                actorEmail,
                event.getEntityType(),
                event.getEntityId(),
                event.getAction(),
                details);
    }
}
