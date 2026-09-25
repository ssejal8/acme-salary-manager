import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { Department } from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { PageResponse, emptyPage } from '../../../shared/page-response';
import { PayslipQuery, PayslipRow } from '../payslip.models';
import { PayslipService } from '../payslip.service';
import { PayslipRegister } from './payslip-register';

const DEPARTMENTS: Department[] = [
  { id: 1, code: 'ENG', name: 'Engineering' },
  { id: 2, code: 'FIN', name: 'Finance' },
];

function row(overrides: Partial<PayslipRow> = {}): PayslipRow {
  return {
    id: 90,
    runId: 7,
    periodYear: 2026,
    periodMonth: 8,
    period: '2026-08',
    runStatus: 'FINALISED',
    published: true,
    employeeId: 1001,
    employeeCode: 'E-1001',
    employeeName: 'Asha Menon',
    department: 'Engineering',
    totalDays: 31,
    paidDays: 31,
    lopDays: 0,
    grossPay: '150000.00',
    totalDeductions: '9200.00',
    netPay: '140800.00',
    ...overrides,
  };
}

function page(rows: PayslipRow[], overrides: Partial<PageResponse<PayslipRow>> = {}) {
  return {
    content: rows,
    page: 0,
    size: 25,
    totalElements: rows.length,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
    ...overrides,
  };
}

describe('PayslipRegister', () => {
  let fixture: ComponentFixture<PayslipRegister>;
  let component: PayslipRegister;

  let queries: PayslipQuery[];
  let searchResult: () => Observable<PageResponse<PayslipRow>>;
  let departmentsResult: () => Observable<Department[]>;

  beforeEach(() => {
    queries = [];
    searchResult = () => of(page([row(), row({ id: 91, employeeId: 1002, employeeName: 'Ravi Iyer' })]));
    departmentsResult = () => of(DEPARTMENTS);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: PayslipService,
          useValue: {
            search: (query: PayslipQuery) => {
              queries.push(query);
              return searchResult();
            },
          },
        },
        {
          provide: ReferenceDataService,
          useValue: { departments: () => departmentsResult() },
        },
      ],
    });
  });

  async function createComponent(employeeId?: string): Promise<void> {
    fixture = TestBed.createComponent(PayslipRegister);
    component = fixture.componentInstance;
    if (employeeId !== undefined) {
      fixture.componentRef.setInput('employeeId', employeeId);
    }
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

  function latest(): PayslipQuery {
    return queries[queries.length - 1];
  }

  it('asks for the first page, newest period first', async () => {
    await createComponent();

    expect(latest()).toMatchObject({ page: 0, size: 25, sort: 'period', direction: 'desc' });
    expect(latest().periodYear).toBeUndefined();
    expect(latest().departmentId).toBeUndefined();
  });

  it('shows each row with its employee, department and figures', async () => {
    await createComponent();

    expect(text()).toContain('Asha Menon');
    expect(text()).toContain('E-1001');
    expect(text()).toContain('Engineering');
    expect(text()).toContain('₹140,800.00');
    expect(text()).toContain('August 2026');
  });

  it('marks a draft payslip as unpublished rather than hiding it', async () => {
    // HR reviews a draft run's payslips before anyone else can see them, so whether the
    // employee sees the same figures matters on this screen.
    searchResult = () => of(page([row({ published: false, runStatus: 'DRAFT' })]));
    await createComponent();

    expect(text()).toContain('DRAFT');
    expect(text()).not.toContain('Published');
  });

  describe('filtering', () => {
    it('sends a period only when both the month and the year are chosen', async () => {
      // The API ignores a month on its own: "March" of no particular year would match
      // every March on record.
      await createComponent();

      component.setPeriodMonth('8');
      await settle();
      expect(latest().periodYear).toBeUndefined();
      expect(latest().periodMonth).toBeUndefined();

      component.setPeriodYear('2026');
      await settle();

      expect(latest()).toMatchObject({ periodYear: 2026, periodMonth: 8 });
    });

    it('reads as the register once a whole period is chosen', async () => {
      await createComponent();

      component.setPeriodMonth('8');
      component.setPeriodYear('2026');
      await settle();

      expect(component.isRegister()).toBe(true);
      expect(text()).toContain('Payroll register');
    });

    it('filters by department', async () => {
      await createComponent();

      component.setDepartment('2');
      await settle();

      expect(latest().departmentId).toBe(2);
    });

    it('goes back to the first page whenever a filter changes', async () => {
      // Page 4 of the previous result is not page 4 of this one.
      searchResult = () => of(page([row()], { page: 3, hasPrevious: true, totalPages: 5 }));
      await createComponent();

      component.load(3);
      await settle();
      component.setDepartment('1');
      await settle();

      expect(latest().page).toBe(0);
    });

    it('clears every filter at once', async () => {
      await createComponent();
      component.setPeriodMonth('8');
      component.setPeriodYear('2026');
      component.setDepartment('1');
      await settle();

      component.clearFilters();
      await settle();

      expect(latest().periodYear).toBeUndefined();
      expect(latest().periodMonth).toBeUndefined();
      expect(latest().departmentId).toBeUndefined();
    });

    it('takes an employee filter from the URL, which is how the record links in', async () => {
      await createComponent('1001');

      expect(latest().employeeId).toBe(1001);
      expect(text()).toContain('employee #1001');
    });

    it('ignores an employee id that is not a valid reference', async () => {
      await createComponent('abc');

      expect(latest().employeeId).toBeUndefined();
    });
  });

  describe('sorting', () => {
    it('sends a key from the API whitelist', async () => {
      // An unlisted key is a 400, not an ignored parameter.
      await createComponent();

      component.sortBy('netPay');
      await settle();

      expect(latest()).toMatchObject({ sort: 'netPay', direction: 'asc' });
    });

    it('toggles direction when the same column is clicked again', async () => {
      await createComponent();

      component.sortBy('grossPay');
      await settle();
      expect(latest().direction).toBe('asc');

      component.sortBy('grossPay');
      await settle();

      expect(latest().direction).toBe('desc');
    });

    it('announces the ordering rather than only showing an arrow', async () => {
      await createComponent();

      component.sortBy('netPay');
      await settle();

      expect(component.ariaSort('netPay')).toBe('ascending');
      expect(component.ariaSort('grossPay')).toBe('none');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[aria-sort="ascending"]'),
      ).not.toBeNull();
    });

    it('sorts in the database, going back to the first page', async () => {
      await createComponent();

      component.sortBy('netPay');
      await settle();

      expect(latest().page).toBe(0);
    });
  });

  describe('paging', () => {
    it('pages on the server', async () => {
      searchResult = () => of(page([row()], { totalElements: 60, totalPages: 3, hasNext: true }));
      await createComponent();

      component.load(1);
      await settle();

      expect(queries.map((query) => query.page)).toEqual([0, 1]);
    });
  });

  it('says so when nothing matches, and why there might be nothing', async () => {
    searchResult = () => of(emptyPage<PayslipRow>());
    await createComponent();

    expect(text()).toContain('No payslips match');
    expect(text()).toContain('once a payroll run has been started');
  });

  it("shows the server's message on failure", async () => {
    searchResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));
    await createComponent();

    expect(component.errorMessage()).toBe('Not permitted');
    expect(text()).toContain('Not permitted');
  });

  it('still shows the table when the department list could not load', async () => {
    // A filter that failed to populate is not worth an error over the data itself.
    departmentsResult = () => throwError(() => new ApiFailure(500, 'Reference data unavailable'));
    await createComponent();

    expect(component.departments()).toEqual([]);
    expect(component.errorMessage()).toBeNull();
    expect(text()).toContain('Asha Menon');
  });

  it('points at the run for period totals rather than summing a page', async () => {
    await createComponent();

    expect(text()).toContain('Period totals live on the payroll run');
  });
});
