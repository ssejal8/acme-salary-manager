package com.acme.salary.payroll.dto;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Request to recompute a draft run with loss-of-pay adjustments (FR-5.6).
 *
 * <p>The list is the complete picture rather than a patch: an employee absent from it is
 * recomputed at full attendance. As a delta there would be no way to express "clear the
 * LOP I entered by mistake", which is exactly the correction a review screen exists for.
 *
 * <p>An empty or absent list is therefore meaningful and allowed — it recomputes everyone
 * at full attendance, which is how a mistaken adjustment is undone.
 */
public record RecomputePayrollRunRequest(@Valid List<LopAdjustment> adjustments) {

    public List<LopAdjustment> adjustmentsOrEmpty() {
        return adjustments == null ? List.of() : adjustments;
    }
}
