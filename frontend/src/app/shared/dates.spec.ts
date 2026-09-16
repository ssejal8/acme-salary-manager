import { formatIsoDate } from './dates';

describe('calendar date formatting', () => {
  it('formats an ISO date the way the screens display it', () => {
    expect(formatIsoDate('2022-06-01')).toBe('1 Jun 2022');
    expect(formatIsoDate('2026-12-31')).toBe('31 Dec 2026');
  });

  it('does not shift the date, whatever the browser timezone', () => {
    // The bug this whole module exists to avoid. `new Date("2022-06-01")` is UTC
    // midnight, which in any timezone behind UTC renders as 31 May — so a date pipe
    // backed by Date would show every joining date a day early for users west of
    // Greenwich. Payroll eligibility and proration are derived from these dates.
    //
    // The assertion below would fail for a Date-based implementation running in, say,
    // America/New_York, and passes here in every zone because no Date is constructed.
    expect(formatIsoDate('2022-01-01')).toBe('1 Jan 2022');
    expect(formatIsoDate('2022-12-31')).toBe('31 Dec 2022');
  });

  it('drops a leading zero from the day but keeps the month name unambiguous', () => {
    expect(formatIsoDate('2024-04-09')).toBe('9 Apr 2024');
  });

  it('handles every month', () => {
    const formatted = Array.from({ length: 12 }, (_, index) =>
      formatIsoDate(`2025-${String(index + 1).padStart(2, '0')}-15`),
    );

    expect(formatted).toEqual([
      '15 Jan 2025',
      '15 Feb 2025',
      '15 Mar 2025',
      '15 Apr 2025',
      '15 May 2025',
      '15 Jun 2025',
      '15 Jul 2025',
      '15 Aug 2025',
      '15 Sep 2025',
      '15 Oct 2025',
      '15 Nov 2025',
      '15 Dec 2025',
    ]);
  });

  it('ignores a time component if one is ever sent', () => {
    expect(formatIsoDate('2026-09-16T09:00:00Z')).toBe('16 Sep 2026');
  });

  it('shows a dash for an absent date, because absent means "still employed"', () => {
    expect(formatIsoDate(null)).toBe('—');
    expect(formatIsoDate(undefined)).toBe('—');
    expect(formatIsoDate('')).toBe('—');
  });

  it('accepts a caller-supplied fallback, so a badge can render nothing at all', () => {
    expect(formatIsoDate(undefined, '')).toBe('');
  });

  it('passes an unrecognised value through rather than showing "Invalid Date"', () => {
    expect(formatIsoDate('not-a-date')).toBe('not-a-date');
  });
});
