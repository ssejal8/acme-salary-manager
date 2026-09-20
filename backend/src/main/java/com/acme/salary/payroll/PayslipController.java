package com.acme.salary.payroll;

import com.acme.salary.payroll.dto.PayslipDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
