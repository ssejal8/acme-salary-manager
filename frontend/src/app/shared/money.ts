/**
 * Formatting for money that arrives from the API as a decimal string.
 *
 * The rule this file exists to keep is in architecture §6.3: amounts are never parsed into
 * a JavaScript `number`. An IEEE-754 double cannot represent most decimal amounts exactly,
 * so parsing and re-rendering a payslip line can change it — and a displayed figure that
 * disagrees with the server's by a hundredth is exactly the class of bug a payroll system
 * must not have.
 *
 * So the grouping below is done on the digits of the string. It looks like more work than
 * `Intl.NumberFormat` would be, and it is; what it buys is that no arithmetic happens on
 * the client at all, and the character sequence displayed is the one the server sent.
 *
 * Grouping is in threes — `950,000.00` — matching the figures published in the project
 * README, rather than the Indian lakh/crore convention.
 */

/** Single currency by decision (ADR-020); `app.payroll.currency` is INR. */
export const CURRENCY_SYMBOL = '₹';

/**
 * Formats an API amount for display, e.g. `"950000.00"` to `"₹950,000.00"`.
 *
 * @param amount the string exactly as the API sent it, or null/undefined
 * @param fallback what to show when there is no figure — a dash, not a zero, because
 *     "no package assigned" and "paid nothing" are different facts (the compensation
 *     report counts them separately)
 */
export function formatMoney(amount: string | null | undefined, fallback = '—'): string {
  const grouped = formatAmount(amount, '');
  return grouped === '' ? fallback : `${CURRENCY_SYMBOL}${grouped}`;
}

/**
 * Formats an API amount without the currency symbol, for a column whose header already
 * names the currency.
 */
export function formatAmount(amount: string | null | undefined, fallback = '—'): string {
  if (amount === null || amount === undefined || amount.trim() === '') {
    return fallback;
  }

  const trimmed = amount.trim();
  const negative = trimmed.startsWith('-');
  const unsigned = negative ? trimmed.slice(1) : trimmed;

  // Anything that is not a plain decimal is shown as it arrived rather than mangled. The
  // API only sends NUMERIC(12,2), so this is a guard against a contract change, not an
  // expected path.
  if (!/^\d+(\.\d+)?$/.test(unsigned)) {
    return trimmed;
  }

  const [whole, fraction = ''] = unsigned.split('.');
  const paddedFraction = fraction.padEnd(2, '0').slice(0, 2);

  return `${negative ? '-' : ''}${groupDigits(whole)}.${paddedFraction}`;
}

/** Inserts a comma every three digits from the right, on the string. */
function groupDigits(digits: string): string {
  let grouped = '';
  for (let index = 0; index < digits.length; index++) {
    // Count from the right: a separator goes before every third digit except the first.
    const fromRight = digits.length - index;
    if (index > 0 && fromRight % 3 === 0) {
      grouped += ',';
    }
    grouped += digits[index];
  }
  return grouped;
}
