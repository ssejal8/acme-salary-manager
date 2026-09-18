import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
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
  PAGE_SIZE_OPTIONS,
  SortDirection,
  StatusFilter,
} from '../employee.models';
import {
  employeeQueriesEqual,
  parseEmployeeQuery,
  toEmployeeQueryParams,
} from '../employee-query';
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
 * ## The URL is the state
 *
 * There is no local copy of what the table is showing. Every control navigates, the
 * criteria are derived from the query string, and the request pipeline watches that. The
 * cycle is one-way and has a single source:
 *
 * ```
 *   control → router.navigate → query string → criteria → request → table
 * ```
 *
 * It is worth the indirection because it buys four things that a local signal cannot, and
 * that users reasonably expect of a list screen: a filtered list is a **shareable link**;
 * **reload** keeps your place; **Back** undoes your last filter rather than leaving the
 * screen; and a link in a ticket still means what it meant when it was written.
 *
 * The one thing to be careful of is the round trip. A control must not also update local
 * state, or the screen would briefly show state the URL disagrees with. The single
 * exception is the search box — see {@link nameInput}.
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
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

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

  readonly pageSizeOptions = PAGE_SIZE_OPTIONS;

  /**
   * The query string, seeded from the snapshot so the first render has real criteria
   * rather than defaults it would immediately replace.
   */
  private readonly queryParams = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  /**
   * What the table is showing, derived from the URL.
   *
   * The custom equality is load-bearing: the router re-emits its parameter map on every
   * successful navigation and each parse allocates a new object, so without it any
   * navigation would fire a second identical request.
   */
  readonly criteria = computed(() => parseEmployeeQuery(this.queryParams()), {
    equal: employeeQueriesEqual,
  });

  readonly errorMessage = signal<string | null>(null);

  /** False until the first response, so an empty table is not mistaken for no results. */
  readonly loaded = signal(false);

  /**
   * The search box's own value.
   *
   * The one place local state is allowed, because the alternative is visibly worse: bound
   * straight to the URL, each character would have to survive a debounce and a navigation
   * before appearing, and the box would feel broken.
   *
   * A `linkedSignal` rather than a plain one, so it is writable while typing *and* resets
   * when the URL's term changes from elsewhere — which is what makes the box follow the
   * Back button instead of keeping a search the list is no longer showing.
   */
  readonly nameInput = linkedSignal(() => this.criteria().q ?? '');

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
  readonly pageSize = computed(() => this.criteria().size);
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

  /**
   * Whether the requested page is past the end of the result.
   *
   * A consequence of putting the page in the URL: a link shared last week can point at
   * page 9 of a list that is now four pages long, and the server answers that with an
   * empty page. Without this the screen would blame the filters for an empty table.
   */
  readonly pageOutOfRange = computed(
    () => this.loaded() && this.page().content.length === 0 && this.criteria().page > 0,
  );

  constructor() {
    this.nameTyped
      .pipe(debounceTime(SEARCH_DEBOUNCE_MS), distinctUntilChanged(), takeUntilDestroyed())
      // replaceUrl, so a ten-character search leaves one history entry rather than ten —
      // otherwise Back would walk the user through their own typing.
      .subscribe((name) => this.applyQuery({ q: name || undefined }, { replaceUrl: true }));
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
    this.applyQuery({ [filter]: value === '' ? undefined : Number(value) });
  }

  onStatusChange(status: string): void {
    this.applyQuery({ status: status as StatusFilter });
  }

  /**
   * Changes rows per page, and returns to the first page.
   *
   * Keeping the page number would move the user somewhere they did not ask to be: page 5
   * of 20-row pages is row 81, which at 100 rows per page is page 1.
   */
  onPageSizeChange(size: string): void {
    this.applyQuery({ size: Number(size) });
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
    this.applyQuery({ sort: key, direction });
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
    this.applyQuery({ page: Math.max(0, page) }, { resetPage: false });
  }

  clearFilters(): void {
    // Pushed through the debounced stream as well, so a keystroke still in the debounce
    // window cannot re-apply the name the user just cleared. The box itself follows the
    // URL, so it does not need setting here.
    this.nameTyped.next('');
    void this.router.navigate([], { relativeTo: this.route, queryParams: {} });
  }

  /**
   * Navigates to the given change, which is what makes it take effect.
   *
   * `queryParamsHandling` is deliberately unset, so the query string is replaced rather
   * than merged — that is how a filter set back to "any" disappears from the URL instead
   * of lingering as a stale key.
   *
   * @param options.resetPage defaults to true: changing a filter while on page 5 would
   *     otherwise usually land on an empty page, which reads as "no employees" rather than
   *     "you have moved"
   */
  private applyQuery(
    change: Partial<EmployeeQuery>,
    options: { resetPage?: boolean; replaceUrl?: boolean } = {},
  ): void {
    const next: EmployeeQuery = { ...this.criteria(), ...change };
    if (options.resetPage !== false) {
      next.page = 0;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: toEmployeeQueryParams(next),
      replaceUrl: options.replaceUrl ?? false,
    });
  }
}
