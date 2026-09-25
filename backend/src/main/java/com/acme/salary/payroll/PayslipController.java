package com.acme.salary.payroll;

import com.acme.salary.common.web.PageResponse;
import com.acme.salary.common.web.PageableSanitizer;
import com.acme.salary.payroll.dto.PayslipDetailResponse;
import com.acme.salary.payroll.dto.PayslipRowResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payslips (FR-6.1, FR-6.2).
 *
 * <p>The role annotations here are only the outer of two checks. They answer "may this
 * role reach this operation"; whether the caller may have <em>this</em> payslip is decided
 * in {@link PayslipService} against the authenticated principal, because it cannot be known
 * until the row is loaded (architecture §8.1, FR-1.5).
 *
 * <p>{@code /me} takes no id, which is the whole point of the path: there is nothing in the
 * URL to change, so the endpoint cannot be aimed at somebody else however the request is
 * built. {@code /{id}} does take one, and is therefore where the ownership check earns its
 * keep.
 */
@RestController
@RequestMapping("/api/v1/payslips")
@Tag(name = "Payslips", description = "Published payslips, for their owner and for HR")
public class PayslipController {

    /**
     * Client sort key to entity property path (NFR-1.2 keeps sorting to a whitelist).
     *
     * <p>There is deliberately no key for the employee's name. A payslip holds its
     * employee as an id, so the name is resolved from another aggregate after the page has
     * been fetched — offering a sort by it would mean sorting a page rather than the query,
     * which is the lie this application avoids everywhere else.
     */
    private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

    /**
     * Newest period first, then by employee id.
     *
     * <p>The tiebreaker is not decoration: paging without a total order can repeat or skip
     * rows between pages, and a period alone is the same value for every row in a run.
     */
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("run.periodYear"),
            Sort.Order.desc("run.periodMonth"),
            Sort.Order.asc("employeeId"));

    private final PayslipService payslips;

    public PayslipController(PayslipService payslips) {
        this.payslips = payslips;
    }

    @GetMapping("/me")
    // Every authenticated role, not just EMPLOYEE: an HR user who is also on the payroll
    // has payslips of their own, and "my own data" is not a privilege to withhold.
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'EMPLOYEE')")
    @Operation(
            summary = "My own payslips",
            description = """
                    The caller's published payslips, most recent first (FR-6.1).

                    Only finalised runs appear: a draft payslip is not published and its
                    figures may still change (FR-5.8).

                    A caller with no employee record — an ADMIN login provisioned without
                    one — gets an empty list rather than an error. They have none.""")
    public List<PayslipDetailResponse> myPayslips() {
        return payslips.myPayslips();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'EMPLOYEE')")
    @Operation(
            summary = "Search payslips",
            description = """
                    A paged payslip list across runs (FR-6.5). Give a year and a month and
                    it is the payroll register for that period (FR-7.1): one row per
                    employee with paid days, gross, deductions and net.

                    ADMIN and HR see every payslip, draft included. Any other caller is
                    restricted in the query to their own published payslips — this is not
                    a route around `/me`, and because the restriction is applied in the
                    database the totals cannot disclose rows the caller may not have.

                    `periodYear` and `periodMonth` are only honoured together: a month
                    without a year would match that month in every year on record.""")
    public PageResponse<PayslipRowResponse> search(
            @Parameter(description = "Restrict to one payroll run")
            @RequestParam(required = false) Long runId,
            @Parameter(description = "Calendar year of the period; must accompany periodMonth")
            @RequestParam(required = false) Integer periodYear,
            @Parameter(description = "1-based month of the period; must accompany periodYear")
            @RequestParam(required = false) Integer periodMonth,
            @Parameter(description = "Restrict to employees in one department")
            @RequestParam(required = false) Long departmentId,
            @Parameter(description = "Restrict to one employee")
            @RequestParam(required = false) Long employeeId,
            @Parameter(description = "Standard page, size and sort parameters")
            @PageableDefault(size = 20) Pageable pageable) {

        PayslipSearch search = new PayslipSearch(
                runId, periodYear, periodMonth, departmentId, employeeId, false);
        return payslips.search(
                search, PageableSanitizer.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'EMPLOYEE')")
    @Operation(
            summary = "One payslip",
            description = """
                    ADMIN and HR may read any payslip, draft included, because reviewing a
                    draft run is their job. An EMPLOYEE may read only their own, and only
                    once its run is finalised.

                    A payslip the caller may not have answers **404, not 403**. A 403 would
                    confirm the id exists, which would make this endpoint a way to probe
                    how many payslips there are and whose.""")
    public PayslipDetailResponse get(@PathVariable Long id) {
        return payslips.findById(id);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'EMPLOYEE')")
    @Operation(
            summary = "Download a payslip as PDF",
            description = """
                    The same document as `GET /payslips/{id}`, rendered for printing or
                    emailing (FR-6.4).

                    Authorised identically, because it *is* the same read: the ownership
                    check runs once in the service and this endpoint reuses it rather than
                    restating it. A payslip the caller may not have answers 404 here too.

                    A draft is stamped DRAFT on the document itself — HR can download one
                    while reviewing a run, and a page that did not say so could be handed
                    to an employee as final.""")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        // The authorisation decision, the figures and the words are all the service's;
        // this method only chooses a representation.
        PayslipDetailResponse payslip = payslips.findById(id);
        byte[] pdf = PayslipPdfRenderer.render(payslip);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(PayslipPdfRenderer.fileNameFor(payslip))
                                .build()
                                .toString())
                // Explicit, because a browser that does not know the length cannot show
                // progress on a download.
                .contentLength(pdf.length)
                .body(pdf);
    }

    private static Map<String, String> sortableProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("period", "run.periodYear");
        properties.put("periodMonth", "run.periodMonth");
        properties.put("employeeId", "employeeId");
        properties.put("paidDays", "paidDays");
        properties.put("lopDays", "lopDays");
        properties.put("grossPay", "grossPay");
        properties.put("totalDeductions", "totalDeductions");
        properties.put("netPay", "netPay");
        return Collections.unmodifiableMap(properties);
    }
}
