package com.acme.salary.orgdata;

import java.math.BigDecimal;

/**
 * An annual CTC range, with either bound optional.
 *
 * <p>Extracted so the band test has exactly one implementation: {@link Grade} owns the
 * band, and the salary structure feature checks a proposed package against it (FR-4.3)
 * without reaching for the entity.
 *
 * @param min inclusive floor, or null for unbounded below
 * @param max inclusive ceiling, or null for unbounded above
 */
public record CtcBand(BigDecimal min, BigDecimal max) {

    public static final CtcBand UNBOUNDED = new CtcBand(null, null);

    /** An absent bound never rejects, so an unconfigured band accepts anything. */
    public boolean contains(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        boolean aboveFloor = min == null || amount.compareTo(min) >= 0;
        boolean belowCeiling = max == null || amount.compareTo(max) <= 0;
        return aboveFloor && belowCeiling;
    }

    public boolean isConfigured() {
        return min != null || max != null;
    }

    /** Human-readable form for an error message, e.g. {@code 800000.00–1500000.00}. */
    public String describe() {
        if (!isConfigured()) {
            return "unbounded";
        }
        return (min == null ? "unbounded" : min.toPlainString())
                + "–"
                + (max == null ? "unbounded" : max.toPlainString());
    }
}
