import { Pipe, PipeTransform } from '@angular/core';
import { formatAmount, formatMoney } from './money';

/**
 * Renders an API amount string as currency: `{{ totals.netMonthly | money }}`.
 *
 * Pure, because the input is an immutable string — so Angular only re-runs it when the
 * value actually changes.
 */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(amount: string | null | undefined, fallback = '—'): string {
    return formatMoney(amount, fallback);
  }
}

/** The same, without the currency symbol, for a column that names the currency once. */
@Pipe({ name: 'amount' })
export class AmountPipe implements PipeTransform {
  transform(amount: string | null | undefined, fallback = '—'): string {
    return formatAmount(amount, fallback);
  }
}
