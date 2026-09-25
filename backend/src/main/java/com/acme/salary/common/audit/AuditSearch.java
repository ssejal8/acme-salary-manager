package com.acme.salary.common.audit;

import java.time.LocalDate;

/**
 * What an audit-trail query is asking for (FR-8.2).
 *
 * <p>Every field is optional and {@code null} means "do not filter on this", so one query
 * answers the three questions this trail exists for: what did this person change, what has
 * happened to this record, and what happened in this window.
 *
 * <p>The dates are calendar days rather than instants, because that is how the question is
 * asked — "what happened on the 14th" — and the service widens them into a half-open
 * instant range. Passing instants would make the caller responsible for knowing that a
 * day ends at the start of the next one.
 */
public record AuditSearch(
        Long actorUserId,
        String entityType,
        Long entityId,
        String action,
        /** Inclusive. */
        LocalDate from,
        /** Inclusive — the whole of this day is in range. */
        LocalDate to) {

    /** Everything recorded about one record, which is the commonest lookup. */
    public static AuditSearch forEntity(String entityType, Long entityId) {
        return new AuditSearch(null, entityType, entityId, null, null, null);
    }
}
