import { PayrollRunStatus, PayslipLine } from '../payslips/payslip.models';

/**
 * Payroll runs, mirroring the `com.acme.salary.payroll.dto` responses.
 *
 * Every amount is a string at two decimal places, like all money in this API (ADR-006).
 *
 * `PayrollRunStatus` and `PayslipLine` are imported rather than redeclared: a payslip is
 * the same thing whoever is looking at it, and a second copy of either type is a second
 * thing to keep in step with the server.
 */

/** Request body of `POST /payroll-runs` — `StartPayrollRunRequest`. */
export interface StartPayrollRunRequest {
  periodYear: number;
  /** 1-based, as the API has it. */
  periodMonth: number;
}

/**
 * A run without its payslips — `PayrollRunSummaryResponse`.
 *
 * @property period the API's compact `2026-04`. Use the `period` pipe over the year and
 *     month to display it, which is what every other screen does.
 * @property finalisedAt absent until the run is published; Jackson omits nulls.
 */
export interface PayrollRunSummary {
  id: number;
  periodYear: number;
  periodMonth: number;
  period: string;
  status: PayrollRunStatus;
  employeeCount: number;
  totalGross: string;
  totalDeductions: string;
  totalNet: string;
  createdAt: string;
  finalisedAt?: string;
  cancelledAt?: string;
}

/**
 * One payslip as it appears *inside a run* — `PayslipResponse`.
 *
 * Deliberately not the `Payslip` the payslip screens use, because it is not the same
 * payload: a run's payslip names its employee by id only and carries no period of its own,
 * since the run it belongs to supplies both. Typing them as one would mean pretending
 * fields exist that the server does not send.
 */
export interface RunPayslip {
  id: number;
  employeeId: number;
  totalDays: number;
  paidDays: number;
  lopDays: number;
  grossPay: string;
  totalDeductions: string;
  netPay: string;
  netPayInWords: string;
  earnings: PayslipLine[];
  deductions: PayslipLine[];
}

/**
 * A run with every payslip in it — `PayrollRunDetailResponse`, and what starting a run
 * answers with.
 *
 * The payslips are present while the run is a draft, which is the point of FR-5.8: they
 * are computed with the run so there is something to review before anyone commits, and
 * they are not yet visible to the employees concerned.
 */
export interface PayrollRunDetail {
  run: PayrollRunSummary;
  payslips: RunPayslip[];
}
