import { Component, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PeriodPipe } from '../../../shared/dates';
import { MoneyPipe } from '../../../shared/money.pipe';
import { Payslip } from '../payslip.models';
import { PayslipService } from '../payslip.service';

/**
 * One payslip, as a document (FR-6.2).
 *
 * Shows employee identity, the period, attendance, every earning and deduction line, the
 * totals, and net pay in words (FR-6.3) — everything a payslip needs to be read on its
 * own, because it will be: this is also what the PDF (FR-6.4) will render when it lands.
 *
 * ## Nothing here is computed
 *
 * Every figure is the string the server sent. The subtotal rows show the server's
 * `grossPay` and `totalDeductions` rather than a sum of the lines above them — which is
 * exactly what makes the column verifiable, because components are rounded individually
 * before being summed (NFR-3.3). A total computed here could differ from the one that was
 * actually paid.
 *
 * ## A missing payslip and a forbidden one look the same
 *
 * The API answers **404** for a payslip the caller may not have, deliberately: a 403 would
 * confirm the id exists. So this screen does not try to tell the two apart either — it
 * reports what it was told and offers the way back to the list.
 */
@Component({
  selector: 'app-payslip-view',
  imports: [RouterLink, MoneyPipe, PeriodPipe],
  templateUrl: './payslip-view.html',
  styleUrl: './payslip-view.scss',
})
export class PayslipView {
  private readonly payslips = inject(PayslipService);

  /** From the route. A string, because a URL segment is one. */
  readonly id = input.required<string>();

  readonly errorMessage = signal<string | null>(null);
  readonly loaded = signal(false);

  readonly payslip = toSignal(
    toObservable(this.id).pipe(
      tap(() => {
        this.errorMessage.set(null);
        this.loaded.set(false);
      }),
      switchMap((rawId) => {
        const payslipId = Number(rawId);
        if (!Number.isInteger(payslipId) || payslipId <= 0) {
          this.errorMessage.set('That is not a valid payslip reference.');
          this.loaded.set(true);
          return of(null);
        }
        return this.payslips.get(payslipId).pipe(
          tap(() => this.loaded.set(true)),
          catchError((failure: unknown) => {
            this.errorMessage.set(
              failure instanceof ApiFailure ? failure.message : 'This payslip could not be loaded.',
            );
            this.loaded.set(true);
            return of(null);
          }),
        );
      }),
    ),
    { initialValue: null as Payslip | null },
  );
}
