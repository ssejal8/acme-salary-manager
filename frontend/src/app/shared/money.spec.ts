import { CURRENCY_SYMBOL, formatAmount, formatMoney } from './money';

/**
 * These tests are the guard on architecture §6.3: amounts are never parsed into a
 * JavaScript `number`.
 *
 * The cases that matter are not the pretty ones. They are the values where a round trip
 * through a double would change the digits, and the ones where an absent figure must not
 * be shown as zero.
 */
describe('money formatting', () => {
  describe('grouping', () => {
    it('groups thousands, matching the figures published in the README', () => {
      expect(formatAmount('950000.00')).toBe('950,000.00');
      expect(formatAmount('891200.00')).toBe('891,200.00');
      expect(formatAmount('58800.00')).toBe('58,800.00');
    });

    it('leaves amounts below a thousand ungrouped', () => {
      expect(formatAmount('200.00')).toBe('200.00');
      expect(formatAmount('0.00')).toBe('0.00');
    });

    it('groups at each boundary exactly once', () => {
      expect(formatAmount('1000.00')).toBe('1,000.00');
      expect(formatAmount('999999.00')).toBe('999,999.00');
      expect(formatAmount('1000000.00')).toBe('1,000,000.00');
      // The largest value NUMERIC(12,2) can hold.
      expect(formatAmount('9999999999.99')).toBe('9,999,999,999.99');
    });

    it('prefixes the currency symbol when asked for money rather than a bare amount', () => {
      expect(formatMoney('90000.00')).toBe(`${CURRENCY_SYMBOL}90,000.00`);
    });
  });

  describe('digits are preserved exactly', () => {
    it('does not round a value that a double could not represent', () => {
      // 0.1 + 0.2 in IEEE-754 is 0.30000000000000004. Any implementation that parsed
      // this string into a number and re-rendered it could not promise these digits.
      expect(formatAmount('0.30')).toBe('0.30');
      expect(formatAmount('1234567.89')).toBe('1,234,567.89');
    });

    it('preserves precision beyond what a double holds exactly', () => {
      // More significant digits than a double's 15-17; parsing would lose the tail.
      expect(formatAmount('12345678901234567890.55')).toBe('12,345,678,901,234,567,890.55');
    });

    it('pads a single decimal place to two rather than dropping it', () => {
      expect(formatAmount('500.5')).toBe('500.50');
    });

    it('adds the decimal places when the API sends a whole number', () => {
      expect(formatAmount('12')).toBe('12.00');
    });

    it('truncates rather than rounds beyond two places, so no digit is invented', () => {
      // The API sends NUMERIC(12,2), so this should not occur. If it ever does, showing
      // the first two decimals is honest; rounding would display a figure the server
      // never sent.
      expect(formatAmount('10.999')).toBe('10.99');
    });
  });

  describe('absent and unexpected values', () => {
    it('shows a dash for an absent amount rather than a zero', () => {
      // "No package assigned" and "costs nothing" are different facts — the compensation
      // report counts them in separate columns.
      expect(formatAmount(null)).toBe('—');
      expect(formatAmount(undefined)).toBe('—');
      expect(formatAmount('')).toBe('—');
      expect(formatAmount('   ')).toBe('—');
      expect(formatMoney(null)).toBe('—');
    });

    it('accepts a caller-supplied fallback', () => {
      expect(formatAmount(null, 'Not set')).toBe('Not set');
      expect(formatMoney(undefined, 'No package')).toBe('No package');
    });

    it('formats a negative amount with the sign outside the digits', () => {
      expect(formatAmount('-1500.00')).toBe('-1,500.00');
    });

    it('passes through anything that is not a plain decimal instead of mangling it', () => {
      expect(formatAmount('1,000.00')).toBe('1,000.00');
      expect(formatAmount('not-a-number')).toBe('not-a-number');
      expect(formatAmount('NaN')).toBe('NaN');
    });
  });
});
