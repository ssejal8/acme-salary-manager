package com.acme.salary.orgdata;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.money.Money;
import com.acme.salary.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A pay band (FR-3.2). The optional CTC range is the only compensation guard in the
 * system: a salary structure outside its employee's band needs an explicit override
 * reason (FR-4.3, ADR-016).
 */
@Entity
@Table(name = "grades")
public class Grade extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "min_ctc", precision = 12, scale = 2)
    private BigDecimal minCtc;

    @Column(name = "max_ctc", precision = 12, scale = 2)
    private BigDecimal maxCtc;

    protected Grade() {
        // for JPA
    }

    public Grade(String name, BigDecimal minCtc, BigDecimal maxCtc) {
        this.name = requireName(name);
        setBand(minCtc, maxCtc);
    }

    public void rename(String name) {
        this.name = requireName(name);
    }

    /** Either bound may be null, meaning "unbounded on that side". */
    public final void setBand(BigDecimal minCtc, BigDecimal maxCtc) {
        BigDecimal min = minCtc == null ? null : Money.normalize(minCtc);
        BigDecimal max = maxCtc == null ? null : Money.normalize(maxCtc);
        if (min != null && Money.isNegative(min)) {
            throw ValidationException.field("minCtc", "must not be negative");
        }
        if (max != null && Money.isNegative(max)) {
            throw ValidationException.field("maxCtc", "must not be negative");
        }
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw ValidationException.field("maxCtc", "must not be less than minCtc");
        }
        this.minCtc = min;
        this.maxCtc = max;
    }

    /**
     * Whether an annual CTC sits inside this band. An absent bound never rejects, so a
     * grade with no band configured accepts anything.
     */
    public boolean contains(BigDecimal annualCtc) {
        BigDecimal ctc = Money.normalize(annualCtc);
        boolean aboveFloor = minCtc == null || ctc.compareTo(minCtc) >= 0;
        boolean belowCeiling = maxCtc == null || ctc.compareTo(maxCtc) <= 0;
        return aboveFloor && belowCeiling;
    }

    public boolean hasBand() {
        return minCtc != null || maxCtc != null;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ValidationException.field("name", "must not be blank");
        }
        return name.strip();
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMinCtc() {
        return minCtc;
    }

    public BigDecimal getMaxCtc() {
        return maxCtc;
    }
}
