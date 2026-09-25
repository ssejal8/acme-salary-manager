package com.acme.salary.orgdata.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request to add or amend a grade and its CTC band (FR-3.3).
 *
 * <p>Either bound may be omitted, and that is meaningful rather than lazy: an absent bound
 * means unbounded on that side, so a top grade with no ceiling never rejects a package
 * (FR-4.3). {@code null} is therefore not the same as zero, which is why neither is
 * {@code @NotNull}.
 *
 * <p>That {@code maxCtc} must not be below {@code minCtc} is checked by the entity, which
 * owns the invariant — and by {@code ck_grades_ctc} in the schema, so data that bypassed
 * the API cannot hold an impossible band either.
 */
public record SaveGradeRequest(
        @NotBlank @Size(max = 20) String name,
        @DecimalMin("0.00") BigDecimal minCtc,
        @DecimalMin("0.00") BigDecimal maxCtc) {
}
