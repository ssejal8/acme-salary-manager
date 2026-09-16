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

@Pipe({ name: 'isoDate' })
export class IsoDatePipe implements PipeTransform {
  transform(date: string | null | undefined, fallback = '—'): string {
    return formatIsoDate(date, fallback);
  }
}
