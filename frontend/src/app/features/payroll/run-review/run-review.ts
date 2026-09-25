import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { of, switchMap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PeriodPipe } from '../../../shared/dates';
import { MoneyPipe } from '../../../shared/money.pipe';
import { PageResponse, emptyPage, shownRange } from '../../../shared/page-response';
import { PayslipRow } from '../../payslips/payslip.models';
import { LopAdjustment, PayrollRunDetail } from '../payroll-run.models';
import { PayrollRunService } from '../payroll-run.service';

/**
 * Rows per page.
 *
 * Fetched a page at a time from `GET /payroll-runs/{id}/payslips`, so neither the DOM nor
 * the response holds ten thousand payslips. The run detail endpoint still returns them all
 * and is used only for the totals and the status — the figures a header needs.
 */
const ROWS_PER_PAGE = 25;

/** Which irreversible action is waiting for a second click. */
type PendingAction = 'finalise' | 'cancel' | null;

/**
 * Review a payroll run, adjust it, then publish or abandon it (FR-5.5 to FR-5.9).
 *
 * ## Why a review screen exists at all
 *
 * Payslips are computed when the run is started, not when it is finalised (FR-5.8).
 * Finalising publishes the figures already on screen **without recomputing them**, which
 * is the only thing that makes reviewing them meaningful — and the reason this screen
 * shows the same numbers the employees will see rather than a summary of them.
 *
 * ## Loss of pay is a whole picture, not a delta
 *
 * The recompute endpoint takes the complete set of adjustments: an employee absent from
 * it is recomputed at full attendance (FR-5.6). So this screen holds every edit and sends
 * all of them each time, which is also what makes "clear the day I entered by mistake"
 * expressible — as a patch there would be no way to say it.
 *
 * ## Two clicks for anything irreversible
 *
 * Finalising publishes payslips to every employee on the run and cannot be undone;
 * cancelling abandons the draft. Neither happens on a single click — the confirmation
 * states what will happen, in the words of the thing that will happen, rather than asking
 * "are you sure?".
 */
@Component({
  selector: 'app-run-review',
  imports: [RouterLink, MoneyPipe, PeriodPipe],
  templateUrl: './run-review.html',
  styleUrl: './run-review.scss',
})
export class RunReview {
  private readonly runs = inject(PayrollRunService);

  /** Bound from the route. A string, because a URL segment is one. */
  readonly id = input.required<string>();

  readonly detail = signal<PayrollRunDetail | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  /** Loss-of-pay days per employee, as edited on screen but not yet applied. */
  private readonly lopByEmployee = signal(new Map<number, number>());

  readonly working = signal(false);
  readonly actionFailure = signal<ApiFailure | null>(null);
  readonly pendingAction = signal<PendingAction>(null);

  /** What the last successful action did, so the screen can say so. */
  readonly outcome = signal<string | null>(null);

  readonly rowsPage = signal(0);

  constructor() {
    toObservable(this.id)
      .pipe(
        switchMap((rawId) => {
          this.loading.set(true);
          this.loadError.set(null);
          this.actionFailure.set(null);
          this.pendingAction.set(null);
          this.outcome.set(null);
          this.rowsPage.set(0);

          const runId = Number(rawId);
          if (!Number.isInteger(runId) || runId <= 0) {
            this.loadError.set('That is not a valid payroll run reference.');
            this.loading.set(false);
            return of(null);
          }
          return this.runs.get(runId);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (detail) => {
          if (!detail) {
            return;
          }
          this.apply(detail);
          this.loading.set(false);
          this.showRowPage(0);
        },
        error: (failure: unknown) => {
          this.loadError.set(
            failure instanceof ApiFailure
              ? failure.message
              : 'This payroll run could not be loaded.',
          );
          this.loading.set(false);
        },
      });
  }

  /**
   * Adopts a run from the server and reseeds the loss-of-pay edits from it.
   *
   * Seeded from the response rather than preserved across a recompute, because the
   * response is what the run now holds — keeping local edits would let the screen show
   * adjustments the server has not applied.
   */
  private apply(detail: PayrollRunDetail): void {
    this.detail.set(detail);
    const lop = new Map<number, number>();
    for (const payslip of detail.payslips) {
      if (payslip.lopDays > 0) {
        lop.set(payslip.employeeId, payslip.lopDays);
      }
    }
    this.lopByEmployee.set(lop);
  }

  readonly run = computed(() => this.detail()?.run ?? null);

  /**
   * Every payslip in the run, from the detail payload.
   *
   * Not what the table renders — that is {@link rows}, a page at a time — but what the
   * loss-of-pay picture is built from. The recompute endpoint takes the complete set of
   * adjustments (FR-5.6), so a screen that only knew one page would silently clear the
   * unpaid days of everyone on the others.
   */
  readonly payslips = computed(() => this.detail()?.payslips ?? []);

  readonly isDraft = computed(() => this.run()?.status === 'DRAFT');

  /** The page of rows on screen, each carrying its employee's name. */
  readonly rows = signal<PageResponse<PayslipRow>>(emptyPage(ROWS_PER_PAGE));
  readonly rowsLoading = signal(false);

  readonly totalRowPages = computed(() => Math.max(1, this.rows().totalPages));

  /** The 1-based row range shown, for the caption. */
  readonly shownRows = computed(() => {
    const page = this.rows();
    const range = shownRange(page);
    return { from: range.from, to: range.to, total: page.totalElements };
  });

  /**
   * Fetches a page of rows.
   *
   * The page number is held by the server's response rather than by a local signal, so
   * the pager can never claim a page the response does not describe.
   */
  showRowPage(page: number): void {
    const runId = Number(this.id());
    if (!Number.isInteger(runId) || runId <= 0) {
      return;
    }
    const requested = Math.max(0, page);
    this.rowsLoading.set(true);
    this.runs.payslips(runId, requested, ROWS_PER_PAGE).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.rowsPage.set(rows.page);
        this.rowsLoading.set(false);
      },
      error: (failure: unknown) => {
        this.rowsLoading.set(false);
        this.actionFailure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }

  lopFor(employeeId: number): number {
    return this.lopByEmployee().get(employeeId) ?? 0;
  }

  /**
   * Whether any edit differs from what the run currently holds.
   *
   * Compared against the whole run rather than the page on screen, so an adjustment made
   * on page four still counts as pending when page one is showing.
   */
  readonly hasPendingEdits = computed(() => {
    const lop = this.lopByEmployee();
    return this.payslips().some(
      (payslip) => (lop.get(payslip.employeeId) ?? 0) !== payslip.lopDays,
    );
  });

  setLop(employeeId: number, value: string, totalDays: number): void {
    const days = Number(value);
    // Clamped rather than rejected: the server refuses more days than the month has, and
    // an input that cannot express an impossible number is kinder than an error about one.
    const clamped = Number.isFinite(days) ? Math.min(Math.max(Math.trunc(days), 0), totalDays) : 0;

    this.lopByEmployee.update((current) => {
      const next = new Map(current);
      if (clamped === 0) {
        // Removed rather than stored as zero: absent from the request means full
        // attendance, which is exactly what zero days is.
        next.delete(employeeId);
      } else {
        next.set(employeeId, clamped);
      }
      return next;
    });
  }

  /** The complete adjustment list, as the API defines it. */
  private adjustments(): LopAdjustment[] {
    return [...this.lopByEmployee().entries()]
      .map(([employeeId, lopDays]) => ({ employeeId, lopDays }))
      .sort((left, right) => left.employeeId - right.employeeId);
  }

  recompute(): void {
    const run = this.run();
    if (!run || this.working()) {
      return;
    }

    this.working.set(true);
    this.actionFailure.set(null);
    this.outcome.set(null);

    this.runs.recompute(run.id, { adjustments: this.adjustments() }).subscribe({
      next: (detail) => {
        this.apply(detail);
        this.working.set(false);
        // The figures on every row have moved, so the page on screen is refetched rather
        // than left showing what was computed before the adjustment.
        this.showRowPage(this.rowsPage());
        this.outcome.set('Recomputed with the loss-of-pay days below.');
      },
      error: (failure: unknown) => this.failed(failure),
    });
  }

  /** Asks for the second click, naming what it will do. */
  confirm(action: Exclude<PendingAction, null>): void {
    this.pendingAction.set(action);
    this.actionFailure.set(null);
    this.outcome.set(null);
  }

  dismissConfirmation(): void {
    this.pendingAction.set(null);
  }

  finalise(): void {
    const run = this.run();
    if (!run || this.working()) {
      return;
    }

    this.working.set(true);
    this.actionFailure.set(null);

    this.runs.finalise(run.id).subscribe({
      next: (detail) => {
        this.apply(detail);
        this.working.set(false);
        this.pendingAction.set(null);
        // Refetched so the rows report their run as published rather than as a draft.
        this.showRowPage(this.rowsPage());
        this.outcome.set('Finalised. These payslips are now visible to the employees on them.');
      },
      error: (failure: unknown) => this.failed(failure),
    });
  }

  cancelRun(): void {
    const run = this.run();
    if (!run || this.working()) {
      return;
    }

    this.working.set(true);
    this.actionFailure.set(null);

    this.runs.cancel(run.id).subscribe({
      next: (detail) => {
        this.apply(detail);
        this.working.set(false);
        this.pendingAction.set(null);
        this.showRowPage(this.rowsPage());
        this.outcome.set('Cancelled. The period is free to be run again.');
      },
      error: (failure: unknown) => this.failed(failure),
    });
  }

  private failed(failure: unknown): void {
    this.working.set(false);
    this.pendingAction.set(null);
    this.actionFailure.set(failure instanceof ApiFailure ? failure : null);
  }

  /**
   * Why an action was refused.
   *
   * The adjustments come back as a field error on `adjustments` — an employee who is not
   * on the run, a day count the month cannot hold — and that is worth showing verbatim
   * because it names the employee.
   */
  readonly actionError = computed(() => {
    const failure = this.actionFailure();
    if (!failure) {
      return null;
    }
    return failure.messageFor('adjustments') ?? failure.message;
  });
}
