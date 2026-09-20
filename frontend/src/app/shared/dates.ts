import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formatting for the API's plain calendar dates.
 *
 * Angular's `DatePipe` is deliberately not used for these, and the reason is a real bug
 * rather than a preference. A `LocalDate` arrives as `"2022-06-01"`, and
 * `new Date("2022-06-01")` is parsed by the ECMAScript spec as *UTC* midnight. Rendered in
 * any timezone behind UTC, that displays as 31 May — so a joining date would read a day
 * early for every user west of Greenwich, which for a date that payroll eligibility and
 * proration depend on is not a cosmetic problem.
 *
 * These are calendar dates with no time and no zone. Formatting them by splitting the
 * string keeps them that way.
 */

const MONTHS = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

/**
 * Formats `"2022-06-01"` as `"1 Jan 2022"`-style output: `"1 Jun 2022"`.
 *
 * @param fallback what to show when there is no date — an absent `exitDate` means
 *     "still employed", not "unknown"
 */
export function formatIsoDate(date: string | null | undefined, fallback = '—'): string {
  if (!date) {
    return fallback;
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!match) {
    // Not the shape expected. Showing it unchanged beats showing "Invalid Date".
    return date;
  }
  const [, year, month, day] = match;
  const monthName = MONTHS[Number(month) - 1];
  return monthName ? `${Number(day)} ${monthName} ${year}` : date;
}

/** Full month names, for a period heading where the abbreviation reads as clipped. */
const FULL_MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

/**
 * The name of a 1-based month, or an empty string if there is no such month.
 *
 * Exists so a month picker and a period heading spell a month the same way, rather than
 * one of them carrying a second list that could drift from this one.
 */
export function monthName(month: number): string {
  return FULL_MONTHS[month - 1] ?? '';
}

/**
 * Formats a payroll period as `April 2026`.
 *
 * Takes the year and month as numbers rather than parsing the API's `"2026-04"`, because
 * they arrive as numbers too and a period is not a date — it has no day, so there is
 * nothing a `Date` could correctly represent.
 */
export function formatPeriod(year: number, month: number): string {
  const name = FULL_MONTHS[month - 1];
  return name ? `${name} ${year}` : `${year}-${String(month).padStart(2, '0')}`;
}

@Pipe({ name: 'period' })
export class PeriodPipe implements PipeTransform {
  transform(value: { periodYear: number; periodMonth: number }): string {
    return formatPeriod(value.periodYear, value.periodMonth);
  }
}

@Pipe({ name: 'isoDate' })
export class IsoDatePipe implements PipeTransform {
  transform(date: string | null | undefined, fallback = '—'): string {
    return formatIsoDate(date, fallback);
  }
}
