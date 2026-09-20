package com.acme.salary.payroll;

import com.acme.salary.common.audit.AuditAction;
import com.acme.salary.common.audit.AuditEntityType;
import com.acme.salary.common.audit.AuditService;
import com.acme.salary.common.error.ApiError;
import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.employee.EmployeeCompensationContext;
import com.acme.salary.employee.EmployeeService;
import com.acme.salary.payroll.dto.LopAdjustment;
import com.acme.salary.payroll.dto.PayrollRunDetailResponse;
import com.acme.salary.payroll.dto.PayrollRunSummaryResponse;
import com.acme.salary.salarystructure.SalaryStructure;
import com.acme.salary.salarystructure.SalaryStructureRepository;
import com.acme.salary.security.CurrentUser;
import com.acme.salary.security.CurrentUserProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Running payroll (FR-5.1 to FR-5.10).
 *
 * <p>Everything that needs more than one fact lives here: which employees a period
 * includes, which revision governs each of them, and whether a period is already taken.
 * The arithmetic belongs to {@link PayslipCalculator} and the state machine to
 * {@link PayrollRun} — this orchestrates them.
 *
 * <h2>Atomicity</h2>
 *
 * A run is one transaction (FR-5.9). That is the reason ADR-011 chose a synchronous run
 * over a job queue: a failure halfway through must leave no partial run, and with the
 * whole computation inside one transaction the rollback is the database's problem rather
 * than a compensating-action problem. The cost is a request that grows with headcount,
 * with a ceiling of a few thousand employees (NFR-1.3 budgets 60 seconds for 1,000).
 *
 * <h2>Two queries, not two thousand</h2>
 *
 * The cohort and their governing revisions are each read once for the whole run. Per
 * employee it would be two queries each, which at a thousand employees is the difference
 * between seconds and minutes.
 */
@Service
public class PayrollRunService {

    private final PayrollRunRepository runs;
    private final EmployeeService employees;
    private final SalaryStructureRepository structures;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final Clock clock;

    public PayrollRunService(
            PayrollRunRepository runs,
            EmployeeService employees,
            SalaryStructureRepository structures,
            CurrentUserProvider currentUser,
            AuditService audit,
            Clock clock) {
        this.runs = runs;
        this.employees = employees;
        this.structures = structures;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Starts a draft run for a period and computes every payslip in it (FR-5.1, FR-5.8).
     *
     * <p>Payslips are created with the draft rather than on finalisation, so there is
     * something to review before anyone commits.
     *
     * @throws ConflictException if the period already has a draft or finalised run
     *     (FR-5.7), or if nobody is payable in it
     */
    @Transactional
    public PayrollRunDetailResponse start(PayrollPeriod period) {
        CurrentUser actor = currentUser.require();
        requirePeriodHasEnded(period);

        if (runs.existsActiveForPeriod(period.year(), period.month())) {
            throw new ConflictException(
                    "a payroll run for " + period.describe() + " already exists; cancel it to run again");
        }

        PayrollRun run = new PayrollRun(period, actor.id());
        Map<Long, PayslipAmounts> computed = computeFor(period, Map.of());
        if (computed.isEmpty()) {
            // Refused rather than saved empty: an empty run would occupy the period
            // against a retry and publish nothing.
            throw new ConflictException(
                    "no employee is payable for " + period.describe()
                            + "; nobody eligible holds a salary structure effective in that period");
        }
        run.computePayslips(computed);

        PayrollRun saved = runs.save(run);
        audit.record(AuditEntityType.PAYROLL_RUN, saved.getId(), AuditAction.PAYROLL_RUN_CREATED,
                summaryDetails(saved));
        return PayrollRunDetailResponse.from(saved);
    }

    /**
     * Recomputes a draft run, applying loss-of-pay adjustments (FR-5.6).
     *
     * <p>The adjustments are the whole picture, not a delta: an employee absent from the
     * list is recomputed at full attendance. Treating them as a patch would make "clear
     * this LOP" impossible to express.
     */
    @Transactional
    public PayrollRunDetailResponse recompute(Long runId, List<LopAdjustment> adjustments) {
        PayrollRun run = load(runId);
        PayrollPeriod period = run.period();
        Map<Long, Integer> lopByEmployee = validateAdjustments(adjustments, run, period);

        Map<Long, PayslipAmounts> computed = computeFor(period, lopByEmployee);
        if (computed.isEmpty()) {
            throw new ConflictException(
                    "no employee is payable for " + period.describe() + " any longer");
        }
        // The draft check lives in the entity, and this is where it fires for a finalised
        // run — before any adjustment is honoured.
        run.computePayslips(computed);
        return PayrollRunDetailResponse.from(run);
    }

    /** Publishes a draft run (FR-5.5, FR-5.8). Nothing is recomputed. */
    @Transactional
    public PayrollRunDetailResponse finalise(Long runId) {
        CurrentUser actor = currentUser.require();
        PayrollRun run = load(runId);

        run.finalise(clock.instant());
        audit.record(AuditEntityType.PAYROLL_RUN, run.getId(), AuditAction.PAYROLL_RUN_FINALISED,
                summaryDetails(run));
        // Logged with the actor so a published month is always attributable.
        return PayrollRunDetailResponse.from(run);
    }

    /** Abandons a draft run, freeing its period for another attempt (FR-5.7, FR-6.6). */
    @Transactional
    public PayrollRunDetailResponse cancel(Long runId) {
        currentUser.require();
        PayrollRun run = load(runId);

        run.cancel(clock.instant());
        audit.record(AuditEntityType.PAYROLL_RUN, run.getId(), AuditAction.PAYROLL_RUN_CANCELLED,
                summaryDetails(run));
        return PayrollRunDetailResponse.from(run);
    }

    @Transactional(readOnly = true)
    public PayrollRunDetailResponse findById(Long runId) {
        return PayrollRunDetailResponse.from(load(runId));
    }

    /** Runs newest period first, with their totals (FR-5.10). */
    @Transactional(readOnly = true)
    public PageResponse<PayrollRunSummaryResponse> list(Pageable pageable) {
        return PageResponse.of(runs.findAllNewestFirst(pageable), PayrollRunSummaryResponse::from);
    }

    /**
     * Computes what every included employee is owed.
     *
     * <p>The cohort is everyone eligible for the period who also holds a revision
     * effective on its last day (FR-5.2). Someone eligible but unpaid — no package yet —
     * is skipped rather than being given a zero payslip, which is the same treatment
     * compensation analytics gives them.
     */
    private Map<Long, PayslipAmounts> computeFor(PayrollPeriod period, Map<Long, Integer> lopByEmployee) {
        List<EmployeeCompensationContext> cohort =
                employees.payrollCohort(period.firstDay(), period.lastDay());
        if (cohort.isEmpty()) {
            return Map.of();
        }

        List<Long> employeeIds = cohort.stream().map(EmployeeCompensationContext::employeeId).toList();
        Map<Long, SalaryStructure> governing = new LinkedHashMap<>();
        for (SalaryStructure structure :
                structures.findEffectiveOnForEmployees(employeeIds, period.lastDay())) {
            governing.put(structure.getEmployeeId(), structure);
        }

        // LinkedHashMap so payslips keep the cohort's order, which is by employee code.
        Map<Long, PayslipAmounts> computed = new LinkedHashMap<>();
        for (EmployeeCompensationContext employee : cohort) {
            SalaryStructure structure = governing.get(employee.employeeId());
            if (structure == null) {
                continue;
            }
            int lopDays = lopByEmployee.getOrDefault(employee.employeeId(), 0);
            computed.put(employee.employeeId(), computeOne(employee, structure, lopDays, period));
        }
        return computed;
    }

    /**
     * One payslip, with the employee named in any failure.
     *
     * <p>The calculator reports a problem against {@code components}, which is right for
     * the assignment form and useless on a run over a thousand people — so the message is
     * re-framed to say who it is about.
     */
    private PayslipAmounts computeOne(
            EmployeeCompensationContext employee,
            SalaryStructure structure,
            int lopDays,
            PayrollPeriod period) {
        try {
            return PayslipCalculator.compute(
                    structure.toComponentAmounts(), lopDays, period.totalDays());
        } catch (ValidationException e) {
            throw new ValidationException(
                    "payroll for %s cannot be computed: %s".formatted(employee.employeeCode(), e.getMessage()),
                    e.fieldErrors());
        }
    }

    /**
     * Checks the adjustments name real, included employees and sane day counts.
     *
     * <p>An adjustment for somebody not on the run is rejected rather than ignored: it
     * almost always means the wrong employee id, and silently dropping it would leave HR
     * believing an LOP had been applied.
     */
    private Map<Long, Integer> validateAdjustments(
            List<LopAdjustment> adjustments, PayrollRun run, PayrollPeriod period) {
        Map<Long, Integer> lopByEmployee = new LinkedHashMap<>();
        if (adjustments == null || adjustments.isEmpty()) {
            return lopByEmployee;
        }

        List<ApiError.FieldError> problems = new ArrayList<>();
        for (LopAdjustment adjustment : adjustments) {
            Long employeeId = adjustment.employeeId();
            if (lopByEmployee.containsKey(employeeId)) {
                problems.add(new ApiError.FieldError(
                        "adjustments", "employee " + employeeId + " is listed more than once"));
                continue;
            }
            if (run.payslipFor(employeeId).isEmpty()) {
                problems.add(new ApiError.FieldError(
                        "adjustments", "employee " + employeeId + " is not included in this run"));
                continue;
            }
            if (adjustment.lopDays() > period.totalDays()) {
                problems.add(new ApiError.FieldError("adjustments",
                        "employee %d has %d loss-of-pay days but %s has only %d"
                                .formatted(employeeId, adjustment.lopDays(), period.describe(),
                                        period.totalDays())));
                continue;
            }
            lopByEmployee.put(employeeId, adjustment.lopDays());
        }

        if (!problems.isEmpty()) {
            throw new ValidationException("Loss-of-pay adjustments are not valid", problems);
        }
        return lopByEmployee;
    }

    /**
     * A period may only be run once it is over.
     *
     * <p>Not in the requirements, and defensible anyway: proration divides by the days in
     * the month, so running March on the 10th would pay everybody a full month for a month
     * that has not happened. Refusing it is cheaper than explaining the resulting payslips.
     */
    private void requirePeriodHasEnded(PayrollPeriod period) {
        LocalDate today = LocalDate.now(clock);
        if (!period.hasEndedBy(today)) {
            throw ValidationException.field("periodMonth",
                    "%s has not finished yet; payroll can only be run for a completed period"
                            .formatted(period.describe()));
        }
    }

    private PayrollRun load(Long runId) {
        return runs.findWithPayslips(runId)
                .orElseThrow(() -> NotFoundException.of("Payroll run", runId));
    }

    private Map<String, Object> summaryDetails(PayrollRun run) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("period", run.period().describe());
        details.put("status", run.getStatus().name());
        details.put("employeeCount", run.getEmployeeCount());
        details.put("totalGross", run.getTotalGross().toPlainString());
        details.put("totalDeductions", run.getTotalDeductions().toPlainString());
        details.put("totalNet", run.getTotalNet().toPlainString());
        return details;
    }
}
