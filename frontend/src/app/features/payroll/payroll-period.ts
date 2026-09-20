/**
 * The calendar arithmetic the run screen needs, as pure functions.
 *
 * Kept out of the component and tested directly, in the same spirit as
 * `employee-query.ts`: every one of these is a calendar fact with an awkward edge — the
 * period before January is in the previous year, February is 28 days or 29 — and a bug in
 * any of them would offer someone the wrong month to run payroll for.
 *
 * Dates are built with the multi-argument `Date` constructor, which is *local* midnight.
 * That is deliberate and is the whole point of `shared/dates.ts`: `new Date("2026-04-01")`
 * is parsed as UTC midnight and lands on 31 March anywhere behind UTC, which here would
 * make a period look finished a day before it is.
 */

import { monthName } from '../../shared/dates';

/** A payroll period: a calendar month, with `month` 1-based as the API has it. */
export interface Period {
  year: number;
  month: number;
}

/**
 * The earliest year the API accepts, mirroring `PayrollPeriod.MIN_YEAR` and the
 * `ck_payroll_runs_year` check behind it. Offering less would hide periods the API allows;
 * offering more would produce a 400.
 */
export const MIN_PERIOD_YEAR = 2000;

/** The month dropdown's options. */
export const MONTH_OPTIONS: readonly { value: number; label: string }[] = Array.from(
  { length: 12 },
  // Named from `shared/dates` so a month is spelled the same here as in a period heading.
  (_, index) => ({ value: index + 1, label: monthName(index + 1) }),
);

/** The last day of a period, as a local date. */
function lastDayOf(period: Period): Date {
  // Day 0 of the *following* month is the last day of this one, which is how February
  // gets 29 days in a leap year without a rule of its own.
  return new Date(period.year, period.month, 0);
}

/** Local midnight on the given date, so comparisons are by day and not by clock time. */
function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/**
 * Whether a period is over, and so runnable.
 *
 * Matches the server's `PayrollPeriod.hasEndedBy`, including that the last day of the
 * month counts as ended. The server still decides — this browser's clock is not
 * authoritative and may be wrong in either direction — so this exists to keep the screen
 * from offering a month that would only be refused, not to replace the refusal.
 */
export function hasPeriodEnded(period: Period, today: Date): boolean {
  return lastDayOf(period).getTime() <= startOfDay(today).getTime();
}

/**
 * The most recent period that has finished — the one almost every visit wants.
 *
 * Always the previous calendar month, even on the last day of the current one: payroll for
 * September is run in October, and defaulting to a month that ends today would be a guess
 * about which the user meant.
 */
export function lastCompletedPeriod(today: Date): Period {
  const month = today.getMonth(); // 0-based, so this is already the previous month 1-based
  return month === 0
    ? { year: today.getFullYear() - 1, month: 12 }
    : { year: today.getFullYear(), month };
}

/** Calendar days in the period — the denominator proration divides by (FR-5.4). */
export function daysInPeriod(period: Period): number {
  return lastDayOf(period).getDate();
}

/**
 * The years worth offering, newest first.
 *
 * Down to the API's floor rather than to some recent window, because a period the API
 * accepts is a period this screen should be able to ask for — a system adopted mid-year
 * may well need to run the months before it. Future years are not offered: a period that
 * has not happened can never be run.
 */
export function yearOptions(today: Date): number[] {
  const years: number[] = [];
  for (let year = today.getFullYear(); year >= MIN_PERIOD_YEAR; year -= 1) {
    years.push(year);
  }
  return years;
}
