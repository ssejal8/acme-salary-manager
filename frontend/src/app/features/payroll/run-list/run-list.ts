import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiFailure } from '../../../core/http/api-error';
import { PeriodPipe } from '../../../shared/dates';
import { MoneyPipe } from '../../../shared/money.pipe';
import { PageResponse, emptyPage, shownRange } from '../../../shared/page-response';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { PayrollRunStatus, PayrollRunSummary } from '../payroll-run.models';
import { PayrollRunService } from '../payroll-run.service';

/** How a run's status reads to a person, and which badge it wears. */
const STATUS_LABELS: Record<PayrollRunStatus, string> = {
  DRAFT: 'Draft',
  FINALISED: 'Finalised',
  CANCELLED: 'Cancelled',
};

/**
 * Every payroll run, newest period first (FR-5.10).
 *
 * The entry point to the cycle: start a period here, or open a draft to review it. Totals
 * come from the run's own stored columns rather than being summed over payslips, so this
 * list is one query however many people are on the payroll — the API deliberately leaves
 * payslips out of this payload.
 *
 * Paging is the server's, not the browser's, like every other list in this application.
 * It is kept simple here — page forward and back, no sort controls — because a run list
 * grows by twelve rows a year, and the criteria a user would sort by are already the
 * order it arrives in.
 */
@Component({
  selector: 'app-run-list',
  imports: [RouterLink, MoneyPipe, PeriodPipe, EmptyState],
  templateUrl: './run-list.html',
  styleUrl: './run-list.scss',
})
export class RunList {
  private readonly runs = inject(PayrollRunService);

  readonly page = signal<PageResponse<PayrollRunSummary>>(emptyPage());
  readonly loaded = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly range = computed(() => shownRange(this.page()));

  constructor() {
    this.load(0);
  }

  load(pageNumber: number): void {
    this.errorMessage.set(null);
    this.runs.list(pageNumber).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loaded.set(true);
      },
      error: (failure: unknown) => {
        this.errorMessage.set(
          failure instanceof ApiFailure
            ? failure.message
            : 'The payroll runs could not be loaded.',
        );
        this.loaded.set(true);
      },
    });
  }

  statusLabel(status: PayrollRunStatus): string {
    return STATUS_LABELS[status];
  }

  /**
   * Which badge a status wears.
   *
   * A draft is neither good nor bad news — it is unfinished work — so it takes the
   * warning styling rather than the active one, which is reserved for a published month.
   */
  statusClass(status: PayrollRunStatus): string {
    return status === 'FINALISED' ? 'badge--active' : 'badge--inactive';
  }
}
