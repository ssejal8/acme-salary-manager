import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiFailure } from '../../../core/http/api-error';
import { Department } from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { MONTH_OPTIONS, yearOptions } from '../../payroll/payroll-period';
import { MoneyPipe } from '../../../shared/money.pipe';
import { PeriodPipe } from '../../../shared/dates';
import { PageResponse, emptyPage, shownRange } from '../../../shared/page-response';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { PayslipQuery, PayslipRow } from '../payslip.models';
import { PayslipService } from '../payslip.service';

const PAGE_SIZE = 25;

/** The columns this screen offers to sort by, and the API key each one sends. */
const SORTABLE = {
  period: 'period',
  paidDays: 'paidDays',
  grossPay: 'grossPay',
  totalDeductions: 'totalDeductions',
  netPay: 'netPay',
} as const;

type SortColumn = keyof typeof SORTABLE;

/**
 * Every payslip in the organisation, filtered (FR-6.5) — which with a period chosen is the
 * payroll register FR-7.1 asks for: one row per employee with paid days, gross, deductions
 * and net.
 *
 * ## One screen, two requirements
 *
 * They are the same query with a different filter, so they are the same screen. A separate
 * "register" would be this table with the period box pre-filled and a different heading,
 * which is a second thing to maintain for no second capability.
 *
 * ## Everything is the server's
 *
 * Filtering, sorting, paging and counting all happen in the database (NFR-1.2). At ten
 * thousand employees a month is ten thousand rows, so a screen that filtered in the
 * browser would either lie about its totals or fetch the whole payroll to show
 * twenty-five rows of it.
 *
 * ## Why this one does not keep its state in the URL
 *
 * The employee list does, deliberately, because a filtered employee list is a link worth
 * sharing. This screen takes `employeeId` from the query string — that is how the employee
 * record links into it — but keeps the period, department and sort in component state. The
 * criteria here are a momentary lookup rather than a view somebody bookmarks, and the URL
 * machinery is not free: `employee-query.ts` exists because getting it right needs its own
 * validation and its own tests.
 */
@Component({
  selector: 'app-payslip-register',
  imports: [RouterLink, MoneyPipe, PeriodPipe, EmptyState],
  templateUrl: './payslip-register.html',
  styleUrl: './payslip-register.scss',
})
export class PayslipRegister {
  private readonly payslips = inject(PayslipService);
  private readonly referenceData = inject(ReferenceDataService);

  /**
   * Bound from `?employeeId=`, so the employee record can link straight to one person's
   * payslips without this screen needing an employee picker.
   */
  readonly employeeId = input<string>();

  readonly months = MONTH_OPTIONS;
  readonly years = yearOptions(new Date());

  readonly departments = signal<Department[]>([]);
  readonly page = signal<PageResponse<PayslipRow>>(emptyPage(PAGE_SIZE));
  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);

  /** The filters, as chosen on screen. `null` means "any". */
  readonly periodYear = signal<number | null>(null);
  readonly periodMonth = signal<number | null>(null);
  readonly departmentId = signal<number | null>(null);

  readonly sortColumn = signal<SortColumn>('period');
  readonly sortDirection = signal<'asc' | 'desc'>('desc');

  readonly range = computed(() => shownRange(this.page()));

  /** Whether a whole period is chosen, which is what makes this a register. */
  readonly isRegister = computed(() => this.periodYear() !== null && this.periodMonth() !== null);

  /** The employee filter arriving from the URL, or null. */
  readonly filteredEmployeeId = computed(() => {
    const raw = this.employeeId();
    if (raw === undefined) {
      return null;
    }
    const id = Number(raw);
    return Number.isInteger(id) && id > 0 ? id : null;
  });

  constructor() {
    this.referenceData.departments().subscribe({
      next: (departments) => this.departments.set(departments),
      // A filter that could not load is not worth an error banner over the table: the
      // table itself still works, and the department box is simply short.
      error: () => this.departments.set([]),
    });

    // Driven by the input rather than fetched once in the constructor. Inputs are bound
    // after construction, so a constructor-time fetch would have run before
    // `?employeeId=` arrived and quietly ignored it — and navigating from one employee's
    // payslips back to everyone's would not refetch.
    toObservable(this.employeeId)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.load(0));
  }

  private query(page: number): PayslipQuery {
    const year = this.periodYear();
    const month = this.periodMonth();
    return {
      // Sent only as a pair: the API ignores a month without a year, because "March" of
      // no particular year would match every March on record.
      ...(year !== null && month !== null ? { periodYear: year, periodMonth: month } : {}),
      ...(this.departmentId() !== null ? { departmentId: this.departmentId()! } : {}),
      ...(this.filteredEmployeeId() !== null ? { employeeId: this.filteredEmployeeId()! } : {}),
      page,
      size: PAGE_SIZE,
      sort: SORTABLE[this.sortColumn()],
      direction: this.sortDirection(),
    };
  }

  load(page: number): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.payslips.search(this.query(page)).subscribe({
      next: (found) => {
        this.page.set(found);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.errorMessage.set(
          failure instanceof ApiFailure ? failure.message : 'The payslips could not be loaded.',
        );
        this.loading.set(false);
      },
    });
  }

  /** Any filter change goes back to the first page: page 4 of the old result is nothing. */
  applyFilters(): void {
    this.load(0);
  }

  setPeriodYear(value: string): void {
    this.periodYear.set(value === '' ? null : Number(value));
    this.applyFilters();
  }

  setPeriodMonth(value: string): void {
    this.periodMonth.set(value === '' ? null : Number(value));
    this.applyFilters();
  }

  setDepartment(value: string): void {
    this.departmentId.set(value === '' ? null : Number(value));
    this.applyFilters();
  }

  clearFilters(): void {
    this.periodYear.set(null);
    this.periodMonth.set(null);
    this.departmentId.set(null);
    this.applyFilters();
  }

  /**
   * Sorts by a column, toggling direction when it is already the one in use.
   *
   * The key sent is from the API's whitelist — an unlisted key is a 400 rather than an
   * ignored parameter — which is why the columns offered here are a closed set and the
   * employee's name is not among them: the name lives in another aggregate and is resolved
   * after the page is fetched, so sorting by it would sort a page rather than the query.
   */
  sortBy(column: SortColumn): void {
    if (this.sortColumn() === column) {
      this.sortDirection.update((direction) => (direction === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortColumn.set(column);
      this.sortDirection.set(column === 'period' ? 'desc' : 'asc');
    }
    this.load(0);
  }

  /** For `aria-sort`, so the ordering is announced rather than only shown. */
  ariaSort(column: SortColumn): 'ascending' | 'descending' | 'none' {
    if (this.sortColumn() !== column) {
      return 'none';
    }
    return this.sortDirection() === 'asc' ? 'ascending' : 'descending';
  }
}
