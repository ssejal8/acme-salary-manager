package com.acme.salary.payroll;

import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.employee.EmployeeIdentity;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.payroll.dto.PayslipDetailResponse;
import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import com.acme.salary.security.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading payslips (FR-6.1, FR-6.2).
 *
 * <h2>This is where ownership is decided</h2>
 *
 * Architecture §8.1 calls record-level ownership the layer most often missed: a role check
 * answers "may an EMPLOYEE read payslips?" but not "may <em>this</em> employee read
 * <em>this</em> payslip". {@code @PreAuthorize} on the controller cannot answer the second
 * question, because it does not know whose payslip it is until the row is loaded — so the
 * decision lives here, against the authenticated principal (FR-1.5).
 *
 * <p>Two rules, and they compose:
 *
 * <ul>
 *   <li><b>ADMIN and HR may read any payslip</b>, draft or published, because reviewing a
 *       draft run is their job (FR-5.6, FR-6.5).
 *   <li><b>An EMPLOYEE may read only their own, and only once published.</b> A draft
 *       payslip has not been published (FR-5.8), so being the subject of one is not yet
 *       grounds to see it — the figures may still change.
 * </ul>
 *
 * <p>A payslip the caller may not see is reported as <b>404, not 403</b>. A 403 would
 * confirm that the id exists, which turns {@code /payslips/{id}} into an oracle for
 * probing how many payslips there are and whose. The caller learns the same thing either
 * way — they cannot have it — and the log records the real reason.
 */
@Service
public class PayslipService {

    private static final Logger log = LoggerFactory.getLogger(PayslipService.class);

    private final PayslipRepository payslips;
    private final EmployeeService employees;
    private final CurrentUserProvider currentUser;

    public PayslipService(
            PayslipRepository payslips, EmployeeService employees, CurrentUserProvider currentUser) {
        this.payslips = payslips;
        this.employees = employees;
        this.currentUser = currentUser;
    }

    /**
     * The caller's own published payslips, most recent first (FR-6.1).
     *
     * <p>No id in the URL, which is the point of a {@code /me} path: there is nothing to
     * tamper with, so this endpoint cannot be pointed at somebody else however the request
     * is crafted (architecture §8.1).
     *
     * <p>A caller with no employee record — an ADMIN login provisioned without one — gets
     * an empty list rather than an error. They genuinely have no payslips.
     */
    @Transactional(readOnly = true)
    public List<PayslipDetailResponse> myPayslips() {
        CurrentUser caller = currentUser.require();
        Optional<EmployeeIdentity> self = employees.findSelf(caller.id());
        if (self.isEmpty()) {
            log.debug("User {} has no employee record, so no payslips", caller.id());
            return List.of();
        }

        EmployeeIdentity identity = self.get();
        List<PayslipDetailResponse> mine = new ArrayList<>();
        for (Payslip payslip : payslips.findPublishedForEmployee(identity.employeeId())) {
            // The identity is the same for every row, so it is resolved once rather than
            // per payslip.
            mine.add(PayslipDetailResponse.from(payslip, identity));
        }
        return mine;
    }

    /**
     * One payslip, if the caller is allowed it (FR-6.2).
     *
     * @throws NotFoundException if it does not exist, or exists and is not the caller's to
     *     see — deliberately the same answer, see the class comment
     */
    @Transactional(readOnly = true)
    public PayslipDetailResponse findById(Long payslipId) {
        CurrentUser caller = currentUser.require();
        Payslip payslip = payslips.findWithRunAndLines(payslipId)
                .orElseThrow(() -> NotFoundException.of("Payslip", payslipId));

        if (!mayRead(caller, payslip)) {
            log.info("User {} ({}) was refused payslip {} belonging to employee {}",
                    caller.id(), caller.role(), payslipId, payslip.getEmployeeId());
            throw NotFoundException.of("Payslip", payslipId);
        }

        return PayslipDetailResponse.from(payslip, employees.identityOf(payslip.getEmployeeId()));
    }

    /**
     * Whether this caller may read this payslip.
     *
     * <p>Kept as one expression so the whole rule can be read at once, and so a future
     * endpoint — the PDF (FR-6.4) — reuses the decision rather than restating it.
     */
    private boolean mayRead(CurrentUser caller, Payslip payslip) {
        if (caller.role() == Role.ADMIN || caller.role() == Role.HR) {
            return true;
        }
        if (!payslip.getRun().isFinalised()) {
            // Not yet published. Even its subject must not see figures that may change.
            return false;
        }
        return employees.findSelf(caller.id())
                .map(self -> self.employeeId().equals(payslip.getEmployeeId()))
                .orElse(false);
    }
}
