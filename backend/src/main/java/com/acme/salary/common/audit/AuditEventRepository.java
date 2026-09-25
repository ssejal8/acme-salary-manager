package com.acme.salary.common.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Everything recorded about one entity, most recent first. */
    List<AuditEvent> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, Long entityId);

    /**
     * The audit trail, filtered by actor, entity type and date range (FR-8.2).
     *
     * <p>Every filter is optional — a null parameter means "do not filter on this" — so
     * one query answers "what did this person change", "everything that happened to
     * payroll runs" and "what happened last Tuesday".
     *
     * <p>Paged in the database rather than loaded and trimmed (NFR-1.2). This table grows
     * without bound by design: it is the one thing in the schema that is never deleted, so
     * it is also the one place where an unpaged query would eventually stop working.
     *
     * <p>The range is half-open — {@code from} inclusive, {@code to} exclusive — so a
     * caller asking for a single day passes that day and the next, and no row is counted
     * twice by two adjacent queries.
     */
    @Query("""
            SELECT event FROM AuditEvent event
            WHERE (:actorUserId IS NULL OR event.actorUserId = :actorUserId)
              AND (:entityType IS NULL OR event.entityType = :entityType)
              AND (:entityId IS NULL OR event.entityId = :entityId)
              AND (:action IS NULL OR event.action = :action)
              AND (:from IS NULL OR event.occurredAt >= :from)
              AND (:to IS NULL OR event.occurredAt < :to)
            """)
    Page<AuditEvent> search(
            @Param("actorUserId") Long actorUserId,
            @Param("entityType") String entityType,
            @Param("entityId") Long entityId,
            @Param("action") String action,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
