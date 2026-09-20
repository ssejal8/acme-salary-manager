package com.acme.salary.payroll.dto;

import com.acme.salary.payroll.PayrollRun;
import java.util.List;

/**
 * A payroll run with every payslip in it — the review screen's payload (FR-5.6).
 *
 * <p>Composed of the summary rather than repeating its fields, so a client reads the
 * totals the same way whether it fetched a list or one run.
 *
 * <p>The payslips are present while the run is a draft, which is the point: FR-5.8 has
 * them computed with the run so there is something to review before anyone commits. They
 * are not yet visible to the employees concerned — that is what finalising does.
 */
public record PayrollRunDetailResponse(
        PayrollRunSummaryResponse run,
        List<PayslipResponse> payslips) {

    public static PayrollRunDetailResponse from(PayrollRun run) {
        return new PayrollRunDetailResponse(
                PayrollRunSummaryResponse.from(run),
                run.getPayslips().stream().map(PayslipResponse::from).toList());
    }
}
