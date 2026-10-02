package com.acme.salary.common.audit;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

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
     *
     * <p>Built as a criteria query, adding only the filters that were supplied, rather than
     * as JPQL of the form {@code (:from IS NULL OR ...)}. The PostgreSQL driver sends a
     * null timestamp untyped, so PostgreSQL cannot infer the type of {@code :from IS NULL}
     * and the unfiltered query — the one the audit screen opens with — failed with a 500.
     */
    default Page<AuditEvent> search(
            Long actorUserId,
            String entityType,
            Long entityId,
            String action,
            Instant from,
            Instant to,
            Pageable pageable) {
        return findAll((root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorUserId != null) {
                predicates.add(builder.equal(root.get("actorUserId"), actorUserId));
            }
            if (entityType != null) {
                predicates.add(builder.equal(root.get("entityType"), entityType));
            }
            if (entityId != null) {
                predicates.add(builder.equal(root.get("entityId"), entityId));
            }
            if (action != null) {
                predicates.add(builder.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThan(root.get("occurredAt"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }
}
