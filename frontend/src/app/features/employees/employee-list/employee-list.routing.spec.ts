import { Location } from '@angular/common';
import { SpyLocation, provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of } from 'rxjs';
import { ReferenceData } from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { PageResponse } from '../../../shared/page-response';
import { EmployeeQuery, EmployeeSummary } from '../employee.models';
import { EmployeeService } from '../employee.service';
import { EmployeeList } from './employee-list';

/**
 * How the employee list keeps its state in the URL.
 *
 * Separate from `employee-list.spec.ts` because it needs a different setup: the component
 * is mounted on a real route through {@link RouterTestingHarness}, so `ActivatedRoute`,
 * the query string and browser history all behave as they do in the application. The other
 * spec covers behaviour that does not depend on the URL and creates the component
 * directly.
 *
 * What is being protected here is the reason for putting state in the URL at all: a
 * filtered list should be a shareable link, reload should keep your place, and Back should
 * undo your last filter rather than leave the screen.
 */

const EMPTY_REFERENCE: ReferenceData = { departments: [], designations: [], grades: [] };

function employee(overrides: Partial<EmployeeSummary> = {}): EmployeeSummary {
  return {
    id: 1001,
    employeeCode: 'E-1001',
    firstName: 'Asha',
    lastName: 'Menon',
    fullName: 'Asha Menon',
    workEmail: 'asha.menon@acme.test',
    dateOfJoining: '2022-06-01',
    status: 'ACTIVE',
    department: { id: 1, label: 'Engineering' },
    designation: { id: 2, label: 'Senior Software Engineer' },
    grade: { id: 3, label: 'G3' },
    ...overrides,
  };
}

/** A page big enough that paging controls are shown. */
function pagedResult(
  overrides: Partial<PageResponse<EmployeeSummary>> = {},
): PageResponse<EmployeeSummary> {
  return {
    content: [employee()],
    page: 0,
    size: 20,
    totalElements: 240,
    totalPages: 12,
    hasNext: true,
    hasPrevious: false,
    ...overrides,
  };
}

describe('EmployeeList URL synchronisation', () => {
  let queries: EmployeeQuery[];
  let listResult: () => Observable<PageResponse<EmployeeSummary>>;
  let harness: RouterTestingHarness;
  /**
   * The mock location, typed as {@link SpyLocation} for its `urlChanges` log — which
   * records whether each change was a push or a replace, and is the only honest way to
   * assert on history behaviour.
   */
  let location: SpyLocation;

  beforeEach(() => {
    queries = [];
    listResult = () => of(pagedResult());

    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'employees', component: EmployeeList }]),
        // Without these, Back and Forward move the URL without the router noticing, and
        // there is no record of push versus replace.
        provideLocationMocks(),
        {
          provide: EmployeeService,
          useValue: {
            list: (query: EmployeeQuery) => {
              queries.push(query);
              return listResult();
            },
          },
        },
        { provide: ReferenceDataService, useValue: { all: () => of(EMPTY_REFERENCE) } },
      ],
    });
    location = TestBed.inject(Location) as SpyLocation;
  });

  /** Mounts the component at the given URL and settles the first request. */
  async function open(url = '/employees'): Promise<EmployeeList> {
    harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl(url, EmployeeList);
    // Without this the router ignores Back and Forward: `Location.back()` would move the
    // URL while the router state stayed put, and the back-button tests below would pass
    // their URL assertions while proving nothing about the screen. The application gets
    // this listener from `Router.initialNavigation()` during bootstrap.
    TestBed.inject(Router).setUpLocationChangeListener();
    await settle();
    return component;
  }

  async function settle(): Promise<void> {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  /**
   * Goes back one history entry and lets the resulting navigation finish.
   *
   * The extra macrotask matters: `back()` only fires the location change, and the router
   * navigation it triggers is scheduled rather than synchronous — so `whenStable()` alone
   * can return before the route has actually changed.
   */
  async function goBack(): Promise<void> {
    location.back();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await settle();
  }

  /** The current query string, without the leading path. */
  function search(): string {
    const path = location.path();
    const separator = path.indexOf('?');
    return separator === -1 ? '' : path.slice(separator + 1);
  }

  function params(): URLSearchParams {
    return new URLSearchParams(search());
  }

  function lastQuery(): EmployeeQuery {
    return queries[queries.length - 1];
  }

  describe('opening a link', () => {
    it('starts from the defaults when the URL carries nothing', async () => {
      await open('/employees');

      expect(lastQuery()).toMatchObject({
        status: 'ACTIVE_ONLY',
        page: 0,
        size: 20,
        sort: 'employeeCode',
        direction: 'asc',
      });
    });

    it('restores the whole query from a shared link', async () => {
      // The point of the feature: this link means the same thing to whoever opens it.
      await open(
        '/employees?q=asha&departmentId=1&gradeId=30&status=ALL&page=3&size=50&sort=lastName&direction=desc',
      );

      expect(lastQuery()).toEqual({
        q: 'asha',
        departmentId: 1,
        designationId: undefined,
        gradeId: 30,
        status: 'ALL',
        page: 2,
        size: 50,
        sort: 'lastName',
        direction: 'desc',
      });
    });

    it('shows the restored search term in the box', async () => {
      const component = await open('/employees?q=asha');

      expect(component.nameInput()).toBe('asha');
    });

    it('shows the restored size in the selector', async () => {
      const component = await open('/employees?size=50');

      expect(component.pageSize()).toBe(50);
    });

    it('requests one page, not two, for a link with parameters', async () => {
      // The criteria signal has a semantic equality function precisely so the router's
      // re-emission on navigation does not cause a duplicate request.
      await open('/employees?q=asha&page=2');

      expect(queries).toHaveLength(1);
    });

    it('ignores a tampered sort key rather than passing it to the API', async () => {
      // The API answers 400 for a key outside its whitelist, so forwarding this would
      // turn a stale bookmark into an error page.
      await open('/employees?sort=passwordHash');

      expect(lastQuery().sort).toBe('employeeCode');
    });
  });

  describe('writing state into the URL', () => {
    it('puts a filter in the query string', async () => {
      const component = await open();

      component.onReferenceFilterChange('departmentId', '2');
      await settle();

      expect(params().get('departmentId')).toBe('2');
      expect(lastQuery().departmentId).toBe(2);
    });

    it('puts the sort in the query string', async () => {
      const component = await open();

      component.sortBy('lastName');
      await settle();

      expect(params().get('sort')).toBe('lastName');
      expect(params().get('direction')).toBeNull();

      component.sortBy('lastName');
      await settle();

      expect(params().get('direction')).toBe('desc');
    });

    it('writes the page 1-based', async () => {
      const component = await open();

      component.goToPage(2);
      await settle();

      expect(params().get('page')).toBe('3');
      expect(lastQuery().page).toBe(2);
    });

    it('leaves the URL clean when nothing differs from the default', async () => {
      // A freshly opened list should be /employees, not a wall of default parameters.
      await open();

      expect(search()).toBe('');
    });

    it('removes a filter from the URL when it is set back to "any"', async () => {
      const component = await open();
      component.onReferenceFilterChange('departmentId', '2');
      await settle();

      component.onReferenceFilterChange('departmentId', '');
      await settle();

      // Replaced rather than merged, so the key is gone rather than blank — `?departmentId=`
      // would fail to bind to a Long on the server.
      expect(params().has('departmentId')).toBe(false);
      expect(lastQuery().departmentId).toBeUndefined();
    });

    it('drops the page from the URL when a filter resets it', async () => {
      const component = await open('/employees?page=5');

      component.onStatusChange('ALL');
      await settle();

      expect(params().has('page')).toBe(false);
      expect(params().get('status')).toBe('ALL');
    });

    it('empties the query string entirely on clear', async () => {
      const component = await open('/employees?q=asha&departmentId=1&status=ALL&page=3');

      component.clearFilters();
      await settle();

      expect(search()).toBe('');
    });
  });

  describe('rows per page', () => {
    it('writes the chosen size to the URL and refetches', async () => {
      const component = await open();

      component.onPageSizeChange('50');
      await settle();

      expect(params().get('size')).toBe('50');
      expect(lastQuery().size).toBe(50);
    });

    it('returns to the first page, because the old page number means nothing', async () => {
      // Page 5 of 20-row pages is row 81, which at 100 per page is page 1. Keeping the
      // number would move the user somewhere they did not ask to be.
      const component = await open('/employees?page=5');

      component.onPageSizeChange('100');
      await settle();

      expect(lastQuery().page).toBe(0);
      expect(params().has('page')).toBe(false);
    });

    it('drops the size from the URL when set back to the default', async () => {
      const component = await open('/employees?size=100');

      component.onPageSizeChange('20');
      await settle();

      expect(params().has('size')).toBe(false);
      expect(lastQuery().size).toBe(20);
    });

    it('offers only sizes the server will honour', async () => {
      // The server clamps at 100 silently, so offering more would show a size the
      // response then contradicts.
      const component = await open();

      expect(component.pageSizeOptions).toEqual([10, 20, 50, 100]);
      expect(Math.max(...component.pageSizeOptions)).toBe(100);
    });

    it('renders the selector once there are rows', async () => {
      await open();

      const select = harness.routeNativeElement?.querySelector<HTMLSelectElement>('#page-size');
      expect(select).not.toBeNull();
      expect(Array.from(select!.options).map((option) => option.value)).toEqual([
        '10',
        '20',
        '50',
        '100',
      ]);
    });

    it('shows the selector even when everything fits on one page', async () => {
      // With 15 results at 20 per page there is nothing to page through, and choosing 10
      // is still a reasonable thing to want.
      listResult = () =>
        of(pagedResult({ totalElements: 15, totalPages: 1, hasNext: false }));
      await open();

      expect(harness.routeNativeElement?.querySelector('#page-size')).not.toBeNull();
      expect(harness.routeNativeElement?.querySelector('.pagination')).toBeNull();
    });

    it('hides the selector when there are no rows at all', async () => {
      listResult = () =>
        of(pagedResult({ content: [], totalElements: 0, totalPages: 0, hasNext: false }));
      await open();

      expect(harness.routeNativeElement?.querySelector('#page-size')).toBeNull();
    });
  });

  describe('going back', () => {
    it('undoes the last filter instead of leaving the screen', async () => {
      const component = await open();
      component.onReferenceFilterChange('departmentId', '2');
      await settle();

      await goBack();

      expect(params().has('departmentId')).toBe(false);
      expect(lastQuery().departmentId).toBeUndefined();
    });

    it('undoes a page move', async () => {
      const component = await open();
      component.goToPage(3);
      await settle();
      expect(lastQuery().page).toBe(3);

      await goBack();

      expect(lastQuery().page).toBe(0);
    });

    it('undoes a sort', async () => {
      const component = await open();
      component.sortBy('lastName');
      await settle();
      expect(lastQuery().sort).toBe('lastName');

      await goBack();

      expect(lastQuery().sort).toBe('employeeCode');
    });
  });

  /**
   * The search box is a `linkedSignal` over the URL's term, so it has to follow the URL
   * when the URL changes from somewhere other than the box itself — Back, Forward, or a
   * link. Otherwise it would sit there showing a search the list is no longer applying.
   */
  describe('the search box following the URL', () => {
    it('takes a new term from the URL', async () => {
      const component = await open('/employees?q=asha');
      expect(component.nameInput()).toBe('asha');

      await harness.navigateByUrl('/employees?q=ravi', EmployeeList);
      await settle();

      expect(component.nameInput()).toBe('ravi');
      expect(lastQuery().q).toBe('ravi');
    });

    it('empties when the term leaves the URL', async () => {
      const component = await open('/employees?q=asha');

      await harness.navigateByUrl('/employees', EmployeeList);
      await settle();

      expect(component.nameInput()).toBe('');
      expect(lastQuery().q).toBeUndefined();
    });

    it('is emptied by clearing the filters', async () => {
      const component = await open('/employees?q=asha&status=ALL');

      component.clearFilters();
      await settle();

      expect(component.nameInput()).toBe('');
    });

    it('keeps what is being typed rather than waiting for the round trip', async () => {
      // The box must not be bound straight to the URL: each character would have to
      // survive a debounce and a navigation before appearing, and it would feel broken.
      const component = await open();

      component.onNameInput('as');

      expect(component.nameInput()).toBe('as');
      expect(params().has('q')).toBe(false);
    });
  });

  /**
   * A consequence of putting the page in the URL: a link shared last week can point at a
   * page that no longer exists, and the server answers that with an empty page. Blaming
   * the filters would send someone looking for a filter they never set.
   */
  describe('a link to a page that no longer exists', () => {
    beforeEach(() => {
      listResult = () =>
        of(pagedResult({ content: [], page: 8, totalElements: 40, totalPages: 2, hasNext: false }));
    });

    it('says the page is gone rather than blaming the filters', async () => {
      const component = await open('/employees?page=9');

      expect(component.pageOutOfRange()).toBe(true);
      expect(harness.routeNativeElement?.textContent).toContain('That page no longer exists');
      expect(harness.routeNativeElement?.textContent).not.toContain('No employees match');
    });

    it('offers a way back to the first page', async () => {
      const component = await open('/employees?page=9');

      listResult = () => of(pagedResult());
      component.goToPage(0);
      await settle();

      expect(component.pageOutOfRange()).toBe(false);
      expect(params().has('page')).toBe(false);
    });

    it('still blames the filters when the first page is genuinely empty', async () => {
      listResult = () =>
        of(pagedResult({ content: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false }));

      const component = await open('/employees?q=nobody');

      expect(component.pageOutOfRange()).toBe(false);
      expect(harness.routeNativeElement?.textContent).toContain('No employees match');
    });
  });

  /**
   * Whether a change adds a history entry decides what Back does, so the two cases are
   * asserted on `SpyLocation.urlChanges`, where a replace is recorded with a `replace:`
   * prefix.
   */
  describe('history entries', () => {
    /** URL changes recorded since the given point in the log. */
    function changesSince(mark: number): string[] {
      return location.urlChanges.slice(mark);
    }

    it('replaces rather than pushes while typing a search', async () => {
      // Otherwise a ten-character search leaves ten history entries, and Back walks the
      // user back through their own typing.
      const component = await open();
      const mark = location.urlChanges.length;

      component.onNameInput('asha');
      await waitPastDebounce();

      expect(params().get('q')).toBe('asha');
      const changes = changesSince(mark);
      expect(changes).toHaveLength(1);
      expect(changes[0]).toMatch(/^replace: /);
      expect(changes[0]).toContain('q=asha');
    });

    it('records one replace for a whole burst of typing, not one per keystroke', async () => {
      const component = await open();
      const mark = location.urlChanges.length;

      component.onNameInput('a');
      component.onNameInput('as');
      component.onNameInput('ash');
      component.onNameInput('asha');
      await waitPastDebounce();

      expect(changesSince(mark)).toHaveLength(1);
    });

    it('pushes for a deliberate change, so Back is useful', async () => {
      const component = await open();
      const mark = location.urlChanges.length;

      component.onStatusChange('ALL');
      await settle();

      const changes = changesSince(mark);
      expect(changes).toHaveLength(1);
      expect(changes[0]).not.toMatch(/^replace: /);
      expect(changes[0]).toContain('status=ALL');
    });

    it('pushes for a page move and for a size change', async () => {
      const component = await open();

      let mark = location.urlChanges.length;
      component.goToPage(2);
      await settle();
      expect(changesSince(mark)[0]).not.toMatch(/^replace: /);

      mark = location.urlChanges.length;
      component.onPageSizeChange('50');
      await settle();
      expect(changesSince(mark)[0]).not.toMatch(/^replace: /);
    });
  });

  const PAST_DEBOUNCE_MS = 350;

  async function waitPastDebounce(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, PAST_DEBOUNCE_MS));
    await settle();
  }
});
