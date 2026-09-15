package com.acme.salary.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Adds row timestamps for entities whose tables carry {@code created_at} and
 * {@code updated_at}.
 *
 * <p>These are technical timestamps, not the audit trail: who did what, and why, is
 * recorded as an {@code AuditEvent} inside the business transaction (ADR-013). The values
 * come from the application's {@link java.time.Clock} via
 * {@link JpaAuditingConfig#auditingDateTimeProvider}, never from {@code Instant.now()}
 * inline, so a test can control them.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity extends BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
