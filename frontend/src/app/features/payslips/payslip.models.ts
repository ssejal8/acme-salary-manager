import { ComponentType } from '../structures/salary-structure.models';

/**
 * Payslips, mirroring `com.acme.salary.payroll.dto.PayslipDetailResponse`.
 *
 * Every amount is a string at two decimal places, like all money in this API (ADR-006).
 */

/** Mirrors `PayrollRunStatus`. A payslip's visibility follows its run's state. */
export type PayrollRunStatus = 'DRAFT' | 'FINALISED' | 'CANCELLED';

/** Who the payslip is for, as the API reports them. */
export interface PayslipEmployee {
  id: number;
  employeeCode: string;
  fullName: string;
  workEmail: string;
  department: string;
  designation: string;
}

/**
 * One earning or deduction line.
 *
 * The code and name come from the payslip's own stored columns rather than a component
 * definition, so a published payslip reads the same after a component is renamed
 * (ADR-010).
 */
export interface PayslipLine {
  code: string;
  name: string;
  type: ComponentType;
  amount: string;
}

/**
 * One row of a payslip list — the HR-wide search and the payroll register (FR-6.5,
 * FR-7.1), and the table a draft run is reviewed in.
 *
 * Deliberately without the earning and deduction lines: a page of a hundred payslips would
 * carry six hundred line items to render a table that shows none of them. Opening a row
 * fetches that payslip in full.
 */
export interface PayslipRow {
  id: number;
  runId: number;
  periodYear: number;
  periodMonth: number;
  /** `2026-08` — the API's compact form. Use the `period` pipe to display it. */
  period: string;
  runStatus: PayrollRunStatus;
  published: boolean;
  employeeId: number;
  employeeCode: string;
  employeeName: string;
  department: string;
  totalDays: number;
  paidDays: number;
  lopDays: number;
  grossPay: string;
  totalDeductions: string;
  netPay: string;
}

/**
 * What a payslip search sends to `GET /payslips`.
 *
 * `periodYear` and `periodMonth` are only honoured together by the API — a month without
 * a year would match that month in every year on record.
 */
export interface PayslipQuery {
  runId?: number;
  periodYear?: number;
  periodMonth?: number;
  departmentId?: number;
  employeeId?: number;
  /** Zero-based. */
  page?: number;
  /** Capped at 100 by the server. */
  size?: number;
  /** One of the API's whitelisted keys, e.g. `netPay`. */
  sort?: string;
  direction?: 'asc' | 'desc';
}

/**
 * One payslip, complete enough to stand alone (FR-6.2).
 *
 * @property published whether its run is finalised. An employee only ever receives
 *     published payslips, but HR can be shown a draft during review — so the flag is
 *     rendered rather than assumed.
 * @property publishedAt absent while the run is not finalised; Jackson omits nulls.
 * @property netPayInWords FR-6.3, rendered by the server so it cannot disagree with the
 *     figure beside it.
 */
export interface Payslip {
  id: number;
  periodYear: number;
  periodMonth: number;
  /** `2026-04` — the API's compact form. Use `formatPeriod` to display it. */
  period: string;
  runStatus: PayrollRunStatus;
  published: boolean;
  publishedAt?: string;
  employee: PayslipEmployee;
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
