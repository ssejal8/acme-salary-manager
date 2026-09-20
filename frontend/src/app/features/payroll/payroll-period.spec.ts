import {
  MIN_PERIOD_YEAR,
  MONTH_OPTIONS,
  daysInPeriod,
  hasPeriodEnded,
  lastCompletedPeriod,
  yearOptions,
} from './payroll-period';

/**
 * Tested directly rather than through the screen, because every case here is a calendar
 * edge — the month before January, a leap February, the last day of a month — and each one
 * decides whether payroll can be run for a period at all.
 */
describe('payroll periods', () => {
  /** Local noon, so a test can never be read as a timezone conversion of midnight. */
  function on(year: number, month: number, day: number): Date {
    return new Date(year, month - 1, day, 12);
  }

  describe('hasPeriodEnded', () => {
    it('treats a finished month as runnable', () => {
      expect(hasPeriodEnded({ year: 2026, month: 8 }, on(2026, 9, 1))).toBe(true);
    });

    it('treats the current month as not yet runnable', () => {
      // The rule exists because proration divides by the days in the month: running
      // September on the 20th would pay a full month for a month that has not happened.
      expect(hasPeriodEnded({ year: 2026, month: 9 }, on(2026, 9, 20))).toBe(false);
    });

    it('counts the last day of the month as ended, as the server does', () => {
      // Matches PayrollPeriod.hasEndedBy: the last day is "not after" today.
      expect(hasPeriodEnded({ year: 2026, month: 9 }, on(2026, 9, 30))).toBe(true);
      expect(hasPeriodEnded({ year: 2026, month: 9 }, on(2026, 9, 29))).toBe(false);
    });

    it('is not fooled by the time of day', () => {
      // Compared by day, so a run at one minute past midnight on the first is allowed.
      expect(hasPeriodEnded({ year: 2026, month: 9 }, new Date(2026, 9, 1, 0, 1))).toBe(true);
    });

    it('refuses a future year', () => {
      expect(hasPeriodEnded({ year: 2027, month: 1 }, on(2026, 12, 31))).toBe(false);
    });
  });

  describe('lastCompletedPeriod', () => {
    it('is the month just gone', () => {
      expect(lastCompletedPeriod(on(2026, 9, 20))).toEqual({ year: 2026, month: 8 });
    });

    it('crosses the year boundary in January', () => {
      // The trap: month - 1 on a 1-based month gives 0, which is not a month.
      expect(lastCompletedPeriod(on(2026, 1, 5))).toEqual({ year: 2025, month: 12 });
    });

    it('is still the previous month on the last day of this one', () => {
      // September ends today, so it is runnable — but "the month just gone" is what
      // somebody opening this screen on 30 September means, and guessing otherwise would
      // silently run a month they had not chosen.
      expect(lastCompletedPeriod(on(2026, 9, 30))).toEqual({ year: 2026, month: 8 });
    });

    it('always names a period that has ended', () => {
      for (let month = 1; month <= 12; month += 1) {
        const today = on(2026, month, 15);
        expect(hasPeriodEnded(lastCompletedPeriod(today), today)).toBe(true);
      }
    });
  });

  describe('daysInPeriod', () => {
    it('gives the calendar length of the month', () => {
      expect(daysInPeriod({ year: 2026, month: 1 })).toBe(31);
      expect(daysInPeriod({ year: 2026, month: 4 })).toBe(30);
    });

    it('knows February, including a leap year', () => {
      // Which is why the schema permits 28 to 31 paid days rather than assuming 30.
      expect(daysInPeriod({ year: 2026, month: 2 })).toBe(28);
      expect(daysInPeriod({ year: 2024, month: 2 })).toBe(29);
      expect(daysInPeriod({ year: 2000, month: 2 })).toBe(29);
      expect(daysInPeriod({ year: 1900, month: 2 })).toBe(28);
    });
  });

  describe('yearOptions', () => {
    it('runs from this year back to the earliest the API accepts', () => {
      const years = yearOptions(on(2026, 9, 20));

      expect(years[0]).toBe(2026);
      expect(years.at(-1)).toBe(MIN_PERIOD_YEAR);
      expect(years).toHaveLength(2026 - MIN_PERIOD_YEAR + 1);
    });

    it('offers no future year, because a future period can never be run', () => {
      expect(yearOptions(on(2026, 9, 20))).not.toContain(2027);
    });
  });

  describe('MONTH_OPTIONS', () => {
    it('is twelve 1-based months named as the period heading names them', () => {
      expect(MONTH_OPTIONS).toHaveLength(12);
      expect(MONTH_OPTIONS[0]).toEqual({ value: 1, label: 'January' });
      expect(MONTH_OPTIONS[11]).toEqual({ value: 12, label: 'December' });
    });
  });
});
