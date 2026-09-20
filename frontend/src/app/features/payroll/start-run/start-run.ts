import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiFailure } from '../../../core/http/api-error';
import { PeriodPipe, formatPeriod } from '../../../shared/dates';
import { MoneyPipe } from '../../../shared/money.pipe';
import {
  MONTH_OPTIONS,
  Period,
  daysInPeriod,
  hasPeriodEnded,
  lastCompletedPeriod,
  yearOptions,
} from '../payroll-period';
import { PayrollRunDetail } from '../payroll-run.models';
import { PayrollRunService } from '../payroll-run.service';

/**
 * Start a payroll run — `POST /payroll-runs` (FR-5.1).
 *
 * ## One button, and everything it needs said around it
 *
 * The request is two numbers, so the form is trivial and the screen's real work is
 * explaining what pressing the button does. That matters more here than on any other
 * screen in the application: a run computes a month's pay for the entire organisation in
 * one transaction, it takes the period for itself so a second attempt is a 409 until the
 * first is cancelled (FR-5.7), and it can take a minute over a thousand employees
 * (NFR-1.3). None of that is recoverable by guessing from a spinner.
 *
 * What it produces is a **draft**. Payslips are computed with the run rather than at
 * finalisation (FR-5.8), which is exactly why the response is worth rendering: the figures
 * shown here are the figures finalising would publish, unchanged.
 *
 * ## The period rules are the server's; two of them are mirrored in the controls
 *
 * The month dropdown offers twelve months and the year dropdown stops at the current year
 * and at the API's floor, because a future period can never be run and a year outside
 * `PayrollPeriod`'s bounds is a 400. Beyond that the screen asserts nothing: whether a
 * period is taken, and whether anyone in it is payable, are answered by the request.
 *
 * The one rule mirrored rather than deferred is "a period may only be run once it is
 * over", and only because it is cheap to say early — the browser's clock is not
 * authoritative, so the server's refusal still decides, and its message is shown when it
 * disagrees with ours.
 */
@Component({
  selector: 'app-start-run',
  imports: [ReactiveFormsModule, MoneyPipe, PeriodPipe],
  templateUrl: './start-run.html',
  styleUrl: './start-run.scss',
})
export class StartRun {
  private readonly runs = inject(PayrollRunService);
  private readonly formBuilder = inject(FormBuilder);

  readonly months = MONTH_OPTIONS;
  readonly years = yearOptions(new Date());

  /**
   * Defaulted to the month just gone, which is what payroll is almost always being run
   * for. A blank period would be a decision nobody needs to make twelve times a year.
   */
  private readonly initialPeriod = lastCompletedPeriod(new Date());

  readonly form = this.formBuilder.nonNullable.group({
    periodYear: [this.initialPeriod.year, [Validators.required]],
    periodMonth: [this.initialPeriod.month, [Validators.required]],
  });

  readonly starting = signal(false);
  readonly failure = signal<ApiFailure | null>(null);

  /**
   * The period the in-flight request was made for.
   *
   * Held separately from the dropdowns because a minute is long enough for someone to
   * change them while waiting, and a progress message naming a month that is not being
   * computed would be worse than no message.
   */
  readonly runningFor = signal<string | null>(null);

  /** The draft the last successful request produced, or null if there is none yet. */
  readonly started = signal<PayrollRunDetail | null>(null);

  /**
   * The selected period, as a signal.
   *
   * Bridged through `valueChanges` rather than read inside a `computed`: a
   * `FormControl`'s value is not a signal, so a computed over it would be calculated once
   * and never again — the heading would be stuck on the month the screen opened with.
   */
  private readonly selection = toSignal(this.form.valueChanges, {
    initialValue: this.form.getRawValue(),
  });

  readonly period = computed<Period>(() => {
    const selected = this.selection();
    return {
      year: Number(selected.periodYear),
      month: Number(selected.periodMonth),
    };
  });

  readonly periodLabel = computed(() => formatPeriod(this.period().year, this.period().month));

  /** Days in the selected month — the denominator proration divides by (FR-5.4). */
  readonly periodDays = computed(() => daysInPeriod(this.period()));

  /** Whether the selected period is over by this browser's clock. Advisory; see above. */
  readonly periodEnded = computed(() => hasPeriodEnded(this.period(), new Date()));

  /**
   * What is wrong with the period, if anything.
   *
   * The server's message wins where there is one, because it is the authority on both the
   * date and the bounds. Ours only fills the gap before a request is made.
   */
  readonly periodMessage = computed(() => {
    const fromServer =
      this.failure()?.messageFor('periodMonth') ?? this.failure()?.messageFor('periodYear');
    if (fromServer) {
      return fromServer;
    }
    return this.periodEnded()
      ? null
      : `${this.periodLabel()} has not finished yet. Payroll can only be run for a completed period,` +
          ' because proration divides by the days in the month.';
  });

  readonly canStart = computed(() => this.periodEnded() && !this.starting());

  constructor() {
    // A refusal is about the period it was asked for. Once the period changes it is stale,
    // and leaving it in place would attribute the server's complaint — "2026-08 already
    // has a run" — to a month the server never saw.
    this.form.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.failure.set(null));
  }

  start(): void {
    if (!this.canStart()) {
      return;
    }

    this.starting.set(true);
    this.runningFor.set(this.periodLabel());
    this.failure.set(null);
    // Cleared rather than left on screen: a previous month's draft sitting above a request
    // for a different one reads as the result of this one.
    this.started.set(null);

    const { year, month } = this.period();
    this.runs.start({ periodYear: year, periodMonth: month }).subscribe({
      next: (detail) => {
        this.started.set(detail);
        this.starting.set(false);
      },
      error: (failure: unknown) => {
        this.starting.set(false);
        this.failure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }

  /**
   * Whether the failure is one to show above the form as a whole.
   *
   * A period already taken and "nobody is payable" are both 409s with no field attached —
   * they are about the period rather than about what was typed into it — so they belong in
   * the page-level alert and not under a dropdown.
   */
  readonly generalFailure = computed(() => {
    const failure = this.failure();
    if (!failure) {
      return null;
    }
    const aboutTheFields =
      failure.messageFor('periodMonth') !== undefined ||
      failure.messageFor('periodYear') !== undefined;
    return aboutTheFields ? null : failure.message;
  });
}
