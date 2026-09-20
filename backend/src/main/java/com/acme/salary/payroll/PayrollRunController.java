package com.acme.salary.payroll;

import com.acme.salary.common.web.PageResponse;
import com.acme.salary.common.web.PageableSanitizer;
import com.acme.salary.payroll.dto.PayrollRunDetailResponse;
import com.acme.salary.payroll.dto.PayrollRunSummaryResponse;
import com.acme.salary.payroll.dto.RecomputePayrollRunRequest;
import com.acme.salary.payroll.dto.StartPayrollRunRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payroll runs (FR-5.1 to FR-5.10).
 *
 * <p>HTTP concerns only: bind, delegate, choose a status code. Every rule about periods,
 * eligibility and state lives below this class.
 *
 * <p>ADMIN/HR throughout. An employee's own payslips are a different endpoint with an
 * ownership check rather than a relaxation of these (FR-1.5, FR-6.1), and the
 * distinction matters here more than anywhere: a draft run holds every salary in the
 * organisation.
 *
 * <p>The state changes are {@code POST}s to named sub-resources rather than a {@code PATCH}
 * of a status field. Finalising is not "setting status to FINALISED" — it publishes
 * payslips to the employees concerned and makes the run immutable — and a verb makes that
 * legible where a field assignment would not.
 */
@RestController
@RequestMapping("/api/v1/payroll-runs")
@Tag(name = "Payroll runs", description = "Monthly payroll computation, review and publication")
public class PayrollRunController {

    /** Client sort key to entity property (NFR-1.2 keeps sorting to a whitelist). */
    private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Direction.DESC, "periodYear").and(Sort.by(Sort.Direction.DESC, "periodMonth"));

    private final PayrollRunService runs;

    public PayrollRunController(PayrollRunService runs) {
        this.runs = runs;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Start a payroll run",
            description = """
                    Computes a draft run for the period, with one payslip per included
                    employee (FR-5.1, FR-5.8). Nothing is published yet.

                    Included: every employee eligible for the period who also holds a
                    salary structure effective on its last day. Someone eligible but
                    without a package is skipped rather than given a zero payslip.

                    Answers 409 if the period already has a draft or finalised run
                    (FR-5.7), or if nobody in it is payable.""")
    public ResponseEntity<PayrollRunDetailResponse> start(
            @Valid @RequestBody StartPayrollRunRequest request) {
        PayrollRunDetailResponse started = runs.start(request.toPeriod());
        return ResponseEntity
                .created(URI.create("/api/v1/payroll-runs/" + started.run().id()))
                .body(started);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "List payroll runs",
            description = "Newest period first, with totals. Payslips are not included.")
    public PageResponse<PayrollRunSummaryResponse> list(
            @Parameter(description = "Standard page, size and sort parameters")
            @PageableDefault(size = 20) Pageable pageable) {
        return runs.list(PageableSanitizer.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Get a payroll run with every payslip in it")
    public PayrollRunDetailResponse get(@PathVariable Long id) {
        return runs.findById(id);
    }

    @PostMapping("/{id}/recompute")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Recompute a draft run, applying loss-of-pay adjustments",
            description = """
                    Recomputes every payslip from the structures in force, prorating
                    earnings by paid days (FR-5.4, FR-5.6).

                    The adjustments are the complete picture, not a delta: an employee
                    absent from the list is recomputed at full attendance, which is how a
                    mistaken adjustment is undone.

                    Answers 409 for a run that is no longer a draft — a finalised run is
                    immutable (FR-5.5).""")
    public PayrollRunDetailResponse recompute(
            @PathVariable Long id, @Valid @RequestBody RecomputePayrollRunRequest request) {
        return runs.recompute(id, request.adjustmentsOrEmpty());
    }

    @PostMapping("/{id}/finalise")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Finalise a run and publish its payslips",
            description = """
                    Publishes the payslips already computed, **without** recomputing them
                    (FR-5.8) — so the figures that were reviewed are the figures the
                    employees see.

                    Irreversible: a finalised run is immutable, and a correction means
                    cancelling a fresh draft rather than editing this one (FR-6.6).""")
    public PayrollRunDetailResponse finalise(@PathVariable Long id) {
        return runs.finalise(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Cancel a draft run",
            description = """
                    Abandons the draft and frees the period to be run again (FR-5.7). Its
                    payslips stay on record as what was computed, unpublished.""")
    public PayrollRunDetailResponse cancel(@PathVariable Long id) {
        return runs.cancel(id);
    }

    private static Map<String, String> sortableProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("periodYear", "periodYear");
        properties.put("periodMonth", "periodMonth");
        properties.put("status", "status");
        properties.put("employeeCount", "employeeCount");
        properties.put("totalNet", "totalNet");
        properties.put("createdAt", "createdAt");
        return Collections.unmodifiableMap(properties);
    }
}
