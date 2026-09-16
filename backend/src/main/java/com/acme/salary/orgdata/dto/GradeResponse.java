package com.acme.salary.orgdata.dto;

import com.acme.salary.orgdata.Grade;
import java.math.BigDecimal;

/**
 * A pay band as the API exposes it (FR-3.2).
 *
 * <p>The CTC bounds travel with it because they are what makes the grade mean anything to
 * a client: the structure assignment form needs them to warn before a package is rejected
 * for falling outside the band (FR-4.3). Either bound may be null, meaning unbounded on
 * that side — and with {@code non_null} inclusion configured in Jackson, an unbounded side
 * is simply absent from the JSON rather than explicitly null.
 *
 * <p>Amounts are strings at two decimal places like all money in this API (ADR-006).
 */
public record GradeResponse(Long id, String name, BigDecimal minCtc, BigDecimal maxCtc) {

    public static GradeResponse from(Grade grade) {
        return new GradeResponse(grade.getId(), grade.getName(), grade.getMinCtc(), grade.getMaxCtc());
    }
}
