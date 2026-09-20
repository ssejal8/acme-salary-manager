/**
 * Compensation analytics, mirroring the `report` DTOs.
 *
 * Every amount is a string at two decimal places, like all money in this API (ADR-006).
 */

/**
 * Summary statistics for a cohort.
 *
 * @property employeesWithoutPackage counted, not priced. An employee with no package is a
 *     coverage gap a payroll run would skip, so averaging them in as zero would understate
 *     every other figure — the API reports the gap instead.
 * @property medianMonthlyGross sits beside the average deliberately: a few senior packages
 *     skew a mean badly, and the two together say more than either alone.
 */
export interface CompensationMetrics {
  headcount: number;
  employeesWithPackage: number;
  employeesWithoutPackage: number;
  totalMonthlyGross: string;
  totalMonthlyDeductions: string;
  totalMonthlyNet: string;
  totalAnnualCtc: string;
  averageMonthlyGross: string;
  medianMonthlyGross: string;
  lowestMonthlyGross: string;
  highestMonthlyGross: string;
}

/**
 * One row of a grouped report — a department or a grade.
 *
 * @property groupId the department or grade id, so a client can drill through to the
 *     employee list filtered by it.
 * @property shareOfMonthlyGross this group's percentage of the organisation's monthly
 *     gross, as a string. A percentage, not money.
 */
export interface CompensationGroup {
  groupId: number;
  groupName: string;
  metrics: CompensationMetrics;
  shareOfMonthlyGross: string;
}

/**
 * The compensation dashboard payload (FR-7.4).
 *
 * This prices the packages **in force now**. It is not a payroll register: it knows
 * nothing about attendance or loss of pay, so it will differ from an actual month's
 * payroll wherever someone has unpaid days. The screen says so, because a figure labelled
 * "monthly cost" invites exactly that misreading.
 *
 * @property generatedAt when the figures were computed, from the application clock — a
 *     true instant, unlike the calendar dates elsewhere in this API.
 */
export interface CompensationOverview {
  generatedAt: string;
  organisation: CompensationMetrics;
  byDepartment: CompensationGroup[];
  byGrade: CompensationGroup[];
}
