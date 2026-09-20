/**
 * Compensation packages, mirroring the `salarystructure` DTOs.
 *
 * Every monetary field is a **string**, not a number, and that is the server's deliberate
 * choice rather than an accident of typing: `JacksonConfig` serialises each `BigDecimal` as
 * a string at scale 2 so that parsing one into an IEEE-754 double is a visible act rather
 * than a silent precision loss (ADR-006, architecture §6.3).
 */

/** Whether a component adds to pay or is taken off it. */
export type ComponentType = 'EARNING' | 'DEDUCTION';

/** How a component's amount is arrived at. A closed set of two by decision (ADR-016). */
export type CalculationType = 'FLAT' | 'PERCENT_OF_BASIC';

/**
 * One line of a package, showing both what was configured and what it came to.
 *
 * @property configuredValue for `FLAT` the monthly amount, and for `PERCENT_OF_BASIC` a
 *     **percentage** — `"12.00"` means twelve per cent, not twelve rupees. The two must be
 *     formatted differently, which is why `formatPercentage` exists alongside
 *     `formatMoney`.
 * @property monthlyAmount what the component actually comes to, rounded. Rounding happens
 *     per component before any summation (NFR-3.3), so the displayed lines add up to the
 *     displayed totals exactly.
 */
export interface SalaryStructureComponent {
  componentId: number;
  code: string;
  name: string;
  type: ComponentType;
  calculationType: CalculationType;
  configuredValue: string;
  monthlyAmount: string;
}

/** The computed bottom line of a package (FR-4.5). */
export interface StructureTotals {
  basicMonthly: string;
  grossMonthly: string;
  totalDeductions: string;
  netMonthly: string;
  annualCtc: string;
}

/**
 * One revision of an employee's package.
 *
 * Packages are never edited: assigning a new one supersedes the current revision rather
 * than overwriting it (ADR-009), so a list of these is the full compensation history and
 * payroll for an earlier month still sees that month's figures.
 *
 * @property current whether this is the revision in force. Equivalent to `supersededOn`
 *     being absent, and sent explicitly so a client does not have to infer it.
 * @property supersededOn the day a later revision took over. **Absent** while this
 *     revision stands — Jackson omits nulls.
 * @property overrideReason present only where the package was accepted outside the
 *     employee's grade CTC band (FR-4.3). Its presence is the signal, and it is recorded
 *     on the revision and in the audit trail.
 * @property createdAt when the revision was recorded — a true instant, unlike
 *     `effectiveFrom`, which is a calendar date.
 */
export interface SalaryStructure {
  id: number;
  employeeId: number;
  effectiveFrom: string;
  supersededOn?: string;
  current: boolean;
  overrideReason?: string;
  createdAt: string;
  earnings: SalaryStructureComponent[];
  deductions: SalaryStructureComponent[];
  totals: StructureTotals;
}

/**
 * One line of a proposed package.
 *
 * @property value the monthly amount for a flat component, or the percentage for a
 *     percent-of-basic one — sent as a string so no amount is ever routed through a
 *     JavaScript number on its way to the server.
 */
export interface ComponentAssignment {
  componentId: number;
  value: string;
}

/**
 * Request body for both `POST …/preview` and `POST …/salary-structures`.
 *
 * The same shape serves both on purpose: the preview validates exactly what the assignment
 * validates, so anything the form can preview successfully it can also save.
 *
 * @property overrideReason required **only** when the package's annual CTC falls outside
 *     the employee's grade band (FR-4.3). The server decides when that is, and says so as
 *     a field error on this very field — which is how the form knows to ask for one.
 */
export interface AssignSalaryStructureRequest {
  effectiveFrom: string;
  components: ComponentAssignment[];
  overrideReason?: string;
}
