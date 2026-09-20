import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { MoneyPipe, PercentagePipe } from '../../../shared/money.pipe';
import { CompensationGroup, CompensationOverview } from '../compensation.models';
import { CompensationService } from '../compensation.service';

/**
 * What the organisation's current packages cost, by department and by grade (FR-7.4).
 *
 * ## Why this is a bar list rather than a chart
 *
 * The breakdowns are four or five rows of a single measure. A pie would make close values
 * harder to compare, and a bar chart alone would hide the figures people actually need —
 * headcount, gross, how many are priced. So each breakdown is a **table with an inline
 * share bar**: the numbers stay readable as text, and the bar makes the magnitudes
 * comparable at a glance. It is also, inherently, the table view accessibility requires.
 *
 * Every bar is the **same** hue. Shading each one by its own value would be a value-ramp on
 * nominal categories — the category is already named in the row, so a second encoding of
 * the same number adds noise, not information. The hue is the accent, validated for
 * lightness, chroma and contrast against the chart surface.
 *
 * ## Money and percentages
 *
 * Amounts are rendered from the strings the server sent and never parsed (architecture
 * §6.3). The one number this screen does parse is `shareOfMonthlyGross`, and only to set a
 * CSS width — a percentage is not money, no total is derived from it, and a bar's length
 * cannot be expressed as a string. The parse is clamped, so a malformed share cannot
 * produce a bar wider than its track.
 */
@Component({
  selector: 'app-compensation-dashboard',
  imports: [DatePipe, NgTemplateOutlet, RouterLink, MoneyPipe, PercentagePipe],
  templateUrl: './compensation-dashboard.html',
  styleUrl: './compensation-dashboard.scss',
})
export class CompensationDashboard {
  private readonly reports = inject(CompensationService);

  readonly errorMessage = signal<string | null>(null);
  readonly loaded = signal(false);

  readonly overview = toSignal(
    this.reports.overview().pipe(
      tap(() => this.loaded.set(true)),
      catchError((failure: unknown) => {
        this.errorMessage.set(
          failure instanceof ApiFailure
            ? failure.message
            : 'The compensation report could not be loaded.',
        );
        this.loaded.set(true);
        return of(null as CompensationOverview | null);
      }),
    ),
    { initialValue: null as CompensationOverview | null },
  );

  readonly organisation = computed(() => this.overview()?.organisation ?? null);
  readonly byDepartment = computed(() => this.overview()?.byDepartment ?? []);
  readonly byGrade = computed(() => this.overview()?.byGrade ?? []);

  /**
   * Whether anyone is unpriced.
   *
   * Surfaced as a status rather than buried in a tile: an employee with no package is
   * skipped by a payroll run, so the gap is the actionable signal in this whole report.
   */
  readonly coverageGap = computed(() => this.organisation()?.employeesWithoutPackage ?? 0);

  readonly hasNoData = computed(
    () =>
      this.loaded() &&
      this.errorMessage() === null &&
      (this.organisation()?.employeesWithPackage ?? 0) === 0,
  );

  /**
   * A share as a CSS width percentage, clamped to 0–100.
   *
   * The only place this screen turns a server figure into a number. Safe because the
   * result is a bar length rather than an amount: nothing is summed from it, and it is
   * never displayed — the share is printed from its original string beside the bar.
   */
  barWidth(share: string): number {
    const value = Number(share);
    if (!Number.isFinite(value)) {
      return 0;
    }
    return Math.min(100, Math.max(0, value));
  }

  /** Full figures for the bar's native tooltip, so hovering reveals more than it shows. */
  groupDetail(group: CompensationGroup): string {
    const { metrics } = group;
    return (
      `${group.groupName}: ` +
      `gross ${metrics.totalMonthlyGross}, ` +
      `deductions ${metrics.totalMonthlyDeductions}, ` +
      `net ${metrics.totalMonthlyNet}, ` +
      `annual CTC ${metrics.totalAnnualCtc}`
    );
  }

  /** Employees priced out of those counted, e.g. "9 of 11". */
  coverageOf(group: CompensationGroup): string {
    return `${group.metrics.employeesWithPackage} of ${group.metrics.headcount}`;
  }
}
