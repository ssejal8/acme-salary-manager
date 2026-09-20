import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PeriodPipe } from '../../../shared/dates';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { MoneyPipe } from '../../../shared/money.pipe';
import { Payslip } from '../payslip.models';
import { PayslipService } from '../payslip.service';

/**
 * The caller's own payslips, newest first (FR-6.1).
 *
 * The first screen an EMPLOYEE has. Until this existed, an employee could sign in
 * successfully and land on the no-access page, because every route was ADMIN/HR.
 *
 * ## One request, not two
 *
 * `GET /payslips/me` returns each payslip in full — the API made each one stand alone so a
 * PDF or a detail view needs nothing else. So this list renders its rows from the same
 * response rather than fetching a summary and then a detail, and opening one is a
 * navigation rather than a round trip.
 *
 * The payslip view still fetches by id, because a pasted link has to work without visiting
 * the list first.
 */
@Component({
  selector: 'app-my-payslips',
  imports: [RouterLink, MoneyPipe, PeriodPipe, EmptyState],
  templateUrl: './my-payslips.html',
  styleUrl: './my-payslips.scss',
})
export class MyPayslips {
  private readonly payslips = inject(PayslipService);

  readonly errorMessage = signal<string | null>(null);
  readonly loaded = signal(false);

  readonly mine = toSignal(
    this.payslips.mine().pipe(
      tap(() => this.loaded.set(true)),
      catchError((failure: unknown) => {
        this.errorMessage.set(
          failure instanceof ApiFailure ? failure.message : 'Your payslips could not be loaded.',
        );
        this.loaded.set(true);
        return of([] as Payslip[]);
      }),
    ),
    { initialValue: [] as Payslip[] },
  );

  /** True only once loaded and genuinely empty — not while loading, not after a failure. */
  readonly hasNone = computed(
    () => this.loaded() && this.errorMessage() === null && this.mine().length === 0,
  );

  /** The most recent payslip, shown in full so the common case needs no second click. */
  readonly latest = computed(() => this.mine()[0] ?? null);

  readonly earlier = computed(() => this.mine().slice(1));
}
