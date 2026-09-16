import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { ReferenceData } from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { PageResponse } from '../../../shared/page-response';
import { EmployeeQuery, EmployeeSummary } from '../employee.models';
import { EmployeeService } from '../employee.service';
import { EmployeeList } from './employee-list';

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

function page(
  content: EmployeeSummary[],
  overrides: Partial<PageResponse<EmployeeSummary>> = {},
): PageResponse<EmployeeSummary> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    hasNext: false,
    hasPrevious: false,
    ...overrides,
  };
}

const REFERENCE: ReferenceData = {
  departments: [
    { id: 1, code: 'ENG', name: 'Engineering' },
    { id: 2, code: 'FIN', name: 'Finance' },
  ],
  designations: [{ id: 20, title: 'Software Engineer' }],
  grades: [{ id: 30, name: 'G2', minCtc: '800000.00', maxCtc: '1500000.00' }],
};

describe('EmployeeList', () => {
  let fixture: ComponentFixture<EmployeeList>;
  let component: EmployeeList;

  /** Every query the component has issued, newest last. */
  let queries: EmployeeQuery[];
  let listResult: () => Observable<PageResponse<EmployeeSummary>>;
  let referenceResult: () => Observable<ReferenceData>;

  beforeEach(() => {
    queries = [];
    listResult = () => of(page([employee()]));
    referenceResult = () => of(REFERENCE);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: EmployeeService,
          useValue: {
            list: (query: EmployeeQuery) => {
              queries.push(query);
              return listResult();
            },
          },
        },
        { provide: ReferenceDataService, useValue: { all: () => referenceResult() } },
      ],
    });
  });

  /** `toObservable` emits through an effect, so the first request needs a settle. */
  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(EmployeeList);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function queryAll(selector: string): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(selector));
  }

  function lastQuery(): EmployeeQuery {
    return queries[queries.length - 1];
  }

  describe('the first load', () => {
    it("asks for the server's defaults: current staff, by employee code", async () => {
      await createComponent();

      expect(queries).toHaveLength(1);
      expect(lastQuery()).toMatchObject({
        status: 'ACTIVE_ONLY',
        page: 0,
        size: 20,
        sort: 'employeeCode',
        direction: 'asc',
      });
    });

    it('renders a row per employee', async () => {
      listResult = () =>
        of(page([employee(), employee({ id: 1002, employeeCode: 'E-1002', fullName: 'Ravi Shah' })]));

      await createComponent();

      expect(queryAll('tbody tr')).toHaveLength(2);
      expect(text()).toContain('Asha Menon');
      expect(text()).toContain('Ravi Shah');
    });

    it('shows the reference data the employee is expressed in', async () => {
      await createComponent();

      expect(text()).toContain('Engineering');
      expect(text()).toContain('Senior Software Engineer');
      expect(text()).toContain('G3');
    });

    it('shows the joining date without shifting it by a timezone', async () => {
      await createComponent();

      expect(text()).toContain('1 Jun 2022');
    });

    it("reports the server's count rather than the rows on screen", async () => {
      listResult = () => of(page([employee()], { totalElements: 240, totalPages: 12, hasNext: true }));

      await createComponent();

      expect(text()).toContain('of 240');
    });

    it('fills the filter dropdowns from reference data', async () => {
      await createComponent();

      const departmentOptions = queryAll('#filter-department option').map((o) => o.textContent?.trim());
      expect(departmentOptions).toEqual(['Any department', 'Engineering', 'Finance']);
    });
  });

  describe('status and leavers', () => {
    it('marks an active employee and a leaver differently', async () => {
      listResult = () =>
        of(
          page([
            employee(),
            employee({ id: 1012, status: 'INACTIVE', exitDate: '2026-03-31', fullName: 'Nikhil Rao' }),
          ]),
        );

      await createComponent();

      expect(queryAll('.badge--active')).toHaveLength(1);
      expect(queryAll('.badge--inactive')).toHaveLength(1);
      expect(text()).toContain('Left 31 Mar 2026');
    });

    it('asks for leavers only when told to', async () => {
      await createComponent();

      component.onStatusChange('ALL');
      await settle();

      expect(lastQuery().status).toBe('ALL');
    });
  });

  describe('sorting', () => {
    it('re-queries the server rather than reordering the loaded rows', async () => {
      await createComponent();

      component.sortBy('lastName');
      await settle();

      expect(queries).toHaveLength(2);
      expect(lastQuery()).toMatchObject({ sort: 'lastName', direction: 'asc' });
    });

    it('starts a new column ascending', async () => {
      await createComponent();

      component.sortBy('department');
      await settle();

      expect(lastQuery().direction).toBe('asc');
    });

    it('flips direction when the same column is chosen again', async () => {
      await createComponent();

      component.sortBy('lastName');
      await settle();
      component.sortBy('lastName');
      await settle();

      expect(lastQuery()).toMatchObject({ sort: 'lastName', direction: 'desc' });
    });

    it('returns to ascending on a third press', async () => {
      await createComponent();

      component.sortBy('lastName');
      await settle();
      component.sortBy('lastName');
      await settle();
      component.sortBy('lastName');
      await settle();

      expect(lastQuery().direction).toBe('asc');
    });

    it('announces the sorted column and direction', async () => {
      // aria-sort is how a screen reader user learns the table is sorted at all.
      await createComponent();

      expect(component.ariaSortFor('employeeCode')).toBe('ascending');
      expect(component.ariaSortFor('lastName')).toBe('none');

      component.sortBy('lastName');
      await settle();

      expect(component.ariaSortFor('lastName')).toBe('ascending');
      expect(component.ariaSortFor('employeeCode')).toBe('none');
    });

    it('makes each column header an operable control, not a click handler on a cell', async () => {
      await createComponent();

      expect(queryAll('thead button.table__sort')).toHaveLength(component.columns.length);
    });
  });

  describe('paging', () => {
    beforeEach(() => {
      listResult = () =>
        of(page([employee()], { page: 1, totalElements: 60, totalPages: 3, hasNext: true, hasPrevious: true }));
    });

    it('requests the page asked for', async () => {
      await createComponent();

      component.goToPage(2);
      await settle();

      expect(lastQuery().page).toBe(2);
    });

    it('never asks for a page before the first', async () => {
      await createComponent();

      component.goToPage(-1);
      await settle();

      expect(lastQuery().page).toBe(0);
    });

    it('shows the page position from the response', async () => {
      await createComponent();

      expect(text()).toContain('Page 2 of 3');
    });

    it('hides the controls when everything fits on one page', async () => {
      listResult = () => of(page([employee()]));

      await createComponent();

      expect(queryAll('.pagination')).toHaveLength(0);
    });
  });

  describe('filtering', () => {
    it('sends a reference filter as an id', async () => {
      await createComponent();

      component.onReferenceFilterChange('departmentId', '2');
      await settle();

      expect(lastQuery().departmentId).toBe(2);
    });

    it('drops the filter entirely when "any" is chosen', async () => {
      // An empty value must become an absent parameter: `?departmentId=` fails to bind.
      await createComponent();
      component.onReferenceFilterChange('departmentId', '2');
      await settle();

      component.onReferenceFilterChange('departmentId', '');
      await settle();

      expect(lastQuery().departmentId).toBeUndefined();
    });

    it('returns to the first page when a filter changes', async () => {
      // Changing a filter on page 5 would otherwise land on an empty page, which reads
      // as "no employees" rather than "you have moved".
      listResult = () =>
        of(page([employee()], { page: 4, totalElements: 100, totalPages: 5, hasPrevious: true }));
      await createComponent();
      component.goToPage(4);
      await settle();

      component.onReferenceFilterChange('gradeId', '30');
      await settle();

      expect(lastQuery().page).toBe(0);
    });

    it('does not reset the page when only the page changes', async () => {
      listResult = () => of(page([employee()], { totalElements: 100, totalPages: 5, hasNext: true }));
      await createComponent();

      component.goToPage(3);
      await settle();

      expect(lastQuery().page).toBe(3);
    });

    it('offers to clear filters only once one is applied', async () => {
      await createComponent();
      expect(component.hasActiveFilters()).toBe(false);

      component.onReferenceFilterChange('departmentId', '1');
      await settle();

      expect(component.hasActiveFilters()).toBe(true);
    });

    it('treats a non-default status as a filter, because leavers are hidden by default', async () => {
      await createComponent();

      component.onStatusChange('ALL');
      await settle();

      expect(component.hasActiveFilters()).toBe(true);
    });

    it('restores every default on clear', async () => {
      await createComponent();
      component.onReferenceFilterChange('departmentId', '1');
      await settle();
      component.onStatusChange('ALL');
      await settle();

      component.clearFilters();
      await settle();

      expect(component.hasActiveFilters()).toBe(false);
      expect(lastQuery()).toMatchObject({
        status: 'ACTIVE_ONLY',
        page: 0,
        sort: 'employeeCode',
        direction: 'asc',
      });
      expect(lastQuery().departmentId).toBeUndefined();
      expect(component.nameInput()).toBe('');
    });
  });

  /**
   * These use real time rather than Vitest's fake timers. Faking `setTimeout` also stalls
   * the scheduling that `fixture.whenStable()` waits on, so the component never settles
   * and the test times out instead of failing usefully. A real 350ms wait is slower and
   * actually exercises the debounce.
   */
  describe('the name search', () => {
    const PAST_DEBOUNCE_MS = 350;

    async function waitPastDebounce(): Promise<void> {
      await new Promise((resolve) => setTimeout(resolve, PAST_DEBOUNCE_MS));
      await settle();
    }

    it('waits for a pause in typing rather than searching per keystroke', async () => {
      await createComponent();
      const initialCalls = queries.length;

      component.onNameInput('a');
      component.onNameInput('as');
      component.onNameInput('ash');
      component.onNameInput('asha');

      // Nothing yet: the debounce window has not elapsed.
      expect(queries.length).toBe(initialCalls);

      await waitPastDebounce();

      // One request for four keystrokes, not four.
      expect(queries.length).toBe(initialCalls + 1);
      expect(lastQuery().q).toBe('asha');
    });

    it('updates the box immediately, so typing stays responsive', async () => {
      await createComponent();

      component.onNameInput('as');

      expect(component.nameInput()).toBe('as');
    });

    it('trims the search before sending it', async () => {
      await createComponent();

      component.onNameInput('  asha  ');
      await waitPastDebounce();

      expect(lastQuery().q).toBe('asha');
    });

    it('drops the filter when the box is emptied', async () => {
      await createComponent();

      component.onNameInput('asha');
      await waitPastDebounce();
      expect(lastQuery().q).toBe('asha');

      component.onNameInput('');
      await waitPastDebounce();

      expect(lastQuery().q).toBeUndefined();
    });

    it('returns to the first page for a new search', async () => {
      listResult = () => of(page([employee()], { totalElements: 100, totalPages: 5, hasNext: true }));
      await createComponent();
      component.goToPage(3);
      await settle();

      component.onNameInput('asha');
      await waitPastDebounce();

      expect(lastQuery().page).toBe(0);
    });
  });

  describe('when there is nothing to show', () => {
    it('distinguishes "no matches" from "none exist"', async () => {
      listResult = () => of(page([]));
      await createComponent();

      expect(text()).toContain('There are no employees yet');

      component.onReferenceFilterChange('departmentId', '1');
      await settle();

      expect(text()).toContain('No employees match these filters');
    });

    it('does not claim an empty result before the first response arrives', async () => {
      // A never-completing request stands in for a slow one.
      listResult = () => new Observable<PageResponse<EmployeeSummary>>(() => undefined);
      fixture = TestBed.createComponent(EmployeeList);
      component = fixture.componentInstance;
      await fixture.whenStable();
      fixture.detectChanges();

      expect(component.loaded()).toBe(false);
      expect(text()).not.toContain('There are no employees yet');
    });
  });

  describe('when the list cannot be loaded', () => {
    it("shows the server's message", async () => {
      listResult = () => throwError(() => new ApiFailure(403, 'You are not permitted to perform this action'));

      await createComponent();

      expect(component.errorMessage()).toBe('You are not permitted to perform this action');
      expect(text()).toContain('You are not permitted to perform this action');
    });

    it('announces the failure', async () => {
      listResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));

      await createComponent();

      expect(queryAll('[role="alert"]').length).toBeGreaterThan(0);
    });

    it('recovers on the next successful query', async () => {
      listResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();
      expect(component.errorMessage()).not.toBeNull();

      listResult = () => of(page([employee()]));
      component.sortBy('lastName');
      await settle();

      expect(component.errorMessage()).toBeNull();
      expect(text()).toContain('Asha Menon');
    });

    it('keeps the list usable when only the filter dropdowns fail', async () => {
      // Losing three reference tables should not cost the user the employee list.
      referenceResult = () => throwError(() => new ApiFailure(500, 'Reference data unavailable'));

      await createComponent();

      expect(component.errorMessage()).toBeNull();
      expect(text()).toContain('Asha Menon');
      expect(component.reference().departments).toEqual([]);
    });
  });

  it('links each row to the employee record', async () => {
    await createComponent();

    const links = queryAll('a.table__link').map((link) => link.getAttribute('href'));
    expect(links).toContain('/employees/1001');
  });
});
