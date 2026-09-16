import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap, tap } from 'rxjs';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { EMPTY_REFERENCE_DATA } from '../../../core/reference-data/reference-data.models';
import { ApiFailure } from '../../../core/http/api-error';
import { IsoDatePipe } from '../../../shared/dates';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { emptyPage, shownRange } from '../../../shared/page-response';
import {
  DEFAULT_EMPLOYEE_QUERY,
  EmployeeQuery,
  EmployeeSortKey,
  EmployeeSummary,
  SortDirection,
  StatusFilter,
} from '../employee.models';
import { EmployeeService } from '../employee.service';

interface SortableColumn {
  readonly key: EmployeeSortKey;
  readonly label: string;
  /** Right-aligned and monospaced, for codes and dates. */
  readonly numeric?: boolean;
}

/** How long to wait after the last keystroke before searching. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The employee list: paged, filtered and sorted entirely by the server (FR-2.4).
 *
 * Nothing is filtered or sorted in the browser. The consequence worth stating is that the
 * totals and page numbers are real — sorting page 1 of 12 re-queries and gives the first
 * page of the new order, rather than reordering the twenty rows that happen to be loaded.
 *
 * State flows one way. Every control writes to {@link criteria}; that signal is the single
 * input to the request pipeline, and the table renders whatever comes back. There is no
 * path where a control and the table can disagree about what is being shown.
 */
@Component({
  selector: 'app-employee-list',
  imports: [RouterLink, IsoDatePipe, EmptyState],
  templateUrl: './employee-list.html',
  styleUrl: './employee-list.scss',
})
export class EmployeeList {
  private readonly employees = inject(EmployeeService);
  private readonly referenceData = inject(ReferenceDataService);

  readonly columns: readonly SortableColumn[] = [
    { key: 'employeeCode', label: 'Code', numeric: true },
    { key: 'lastName', label: 'Name' },
    { key: 'department', label: 'Department' },
    { key: 'designation', label: 'Designation' },
    { key: 'grade', label: 'Grade' },
    { key: 'dateOfJoining', label: 'Joined', numeric: true },
    { key: 'status', label: 'Status' },
  ];

  readonly statusOptions: readonly { value: StatusFilter; label: string }[] = [
    { value: 'ACTIVE_ONLY', label: 'Current staff' },
    { value: 'INACTIVE_ONLY', label: 'Leavers' },
    { value: 'ALL', label: 'Everyone' },
  ];

  /** The one source of truth for what the table is showing. */
  private readonly criteria = signal<EmployeeQuery>(DEFAULT_EMPLOYEE_QUERY);

  readonly errorMessage = signal<string | null>(null);

  /** False until the first response, so an empty table is not mistaken for no results. */
  readonly loaded = signal(false);

  /**
   * Bound to the search box directly, rather than through {@link criteria}, so the input
   * stays responsive while the request it will trigger is still being debounced.
   */
  readonly nameInput = signal('');

  private readonly nameTyped = new Subject<string>();

  /**
   * Dropdown contents. A failure here is not fatal: the filters fall back to empty and the
   * list still works, which is better than an error screen because someone lost access to
   * three reference tables.
   */
  readonly reference = toSignal(
    this.referenceData.all().pipe(catchError(() => of(EMPTY_REFERENCE_DATA))),
    { initialValue: EMPTY_REFERENCE_DATA },
  );

  /**
   * The current page.
   *
   * `switchMap` rather than `mergeMap` on purpose: it cancels the request in flight when
   * the criteria change again. Without it, two responses could arrive out of order and
   * the table would settle on whichever was slower rather than whichever was asked for
   * last.
   */
  readonly page = toSignal(
    toObservable(this.criteria).pipe(
      tap(() => this.errorMessage.set(null)),
      switchMap((criteria) =>
        this.employees.list(criteria).pipe(
          catchError((failure: unknown) => {
            this.errorMessage.set(
              failure instanceof ApiFailure ? failure.message : 'The employee list could not be loaded.',
            );
            return of(emptyPage<EmployeeSummary>(criteria.size));
          }),
        ),
      ),
      tap(() => this.loaded.set(true)),
    ),
    { initialValue: emptyPage<EmployeeSummary>() },
  );

  readonly sortKey = computed(() => this.criteria().sort);
  readonly sortDirection = computed(() => this.criteria().direction);
  readonly statusFilter = computed(() => this.criteria().status);
  readonly range = computed(() => shownRange(this.page()));

  /** Whether any filter is narrowing the list, which changes what "no results" means. */
  readonly hasActiveFilters = computed(() => {
    const criteria = this.criteria();
    return Boolean(
      criteria.q ||
        criteria.departmentId !== undefined ||
        criteria.designationId !== undefined ||
        criteria.gradeId !== undefined ||
        criteria.status !== DEFAULT_EMPLOYEE_QUERY.status,
    );
  });

  constructor() {
    this.nameTyped
      .pipe(debounceTime(SEARCH_DEBOUNCE_MS), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((name) => this.patch({ q: name || undefined }));
  }

  onNameInput(value: string): void {
    this.nameInput.set(value);
    this.nameTyped.next(value.trim());
  }

  /**
   * Reads a `<select>` whose options are reference-data ids.
   *
   * The empty option means "any", which must become an absent parameter rather than a
   * blank one — `?departmentId=` fails to bind to a `Long` on the server.
   */
  onReferenceFilterChange(
    filter: 'departmentId' | 'designationId' | 'gradeId',
    value: string,
  ): void {
    this.patch({ [filter]: value === '' ? undefined : Number(value) });
  }

  onStatusChange(status: string): void {
    this.patch({ status: status as StatusFilter });
  }

  /**
   * Sorts by a column: same column flips the direction, a new one starts ascending.
   *
   * Starting ascending is the conventional expectation for text and dates alike, and
   * matters more than a per-type default would.
   */
  sortBy(key: EmployeeSortKey): void {
    const criteria = this.criteria();
    const direction: SortDirection =
      criteria.sort === key && criteria.direction === 'asc' ? 'desc' : 'asc';
    this.patch({ sort: key, direction });
  }

  /** What `aria-sort` should be for a column header (WCAG: sort state must be announced). */
  ariaSortFor(key: EmployeeSortKey): 'ascending' | 'descending' | 'none' {
    if (this.sortKey() !== key) {
      return 'none';
    }
    return this.sortDirection() === 'asc' ? 'ascending' : 'descending';
  }

  goToPage(page: number): void {
    // Only the page moves here, so this is the one change that must not reset it.
    this.criteria.update((criteria) => ({ ...criteria, page: Math.max(0, page) }));
  }

  clearFilters(): void {
    this.nameInput.set('');
    // Pushed through the debounced stream as well, so a pending keystroke cannot
    // re-apply the name the user just cleared.
    this.nameTyped.next('');
    this.criteria.set(DEFAULT_EMPLOYEE_QUERY);
  }

  /**
   * Applies a change and returns to the first page.
   *
   * Resetting the page is the important part: changing a filter while on page 5 of the old
   * result would usually land on an empty page, which reads as "no employees" rather than
   * "you have moved".
   */
  private patch(change: Partial<EmployeeQuery>): void {
    this.criteria.update((criteria) => ({ ...criteria, ...change, page: 0 }));
  }
}
