import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PageResponse, emptyPage } from '../../../shared/page-response';
import { PayrollRunSummary } from '../payroll-run.models';
import { PayrollRunService } from '../payroll-run.service';
import { RunList } from './run-list';

function run(overrides: Partial<PayrollRunSummary> = {}): PayrollRunSummary {
  return {
    id: 7,
    periodYear: 2026,
    periodMonth: 8,
    period: '2026-08',
    status: 'DRAFT',
    employeeCount: 9959,
    totalGross: '941288000.00',
    totalDeductions: '58000000.00',
    totalNet: '883288000.00',
    createdAt: '2026-09-01T09:00:00Z',
    ...overrides,
  };
}

function page(
  content: PayrollRunSummary[],
  overrides: Partial<PageResponse<PayrollRunSummary>> = {},
): PageResponse<PayrollRunSummary> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
    ...overrides,
  };
}

describe('RunList', () => {
  let fixture: ComponentFixture<RunList>;
  let component: RunList;

  let requestedPages: number[];
  let listResult: () => Observable<PageResponse<PayrollRunSummary>>;

  beforeEach(() => {
    requestedPages = [];
    listResult = () =>
      of(page([run(), run({ id: 6, periodMonth: 7, period: '2026-07', status: 'FINALISED' })]));

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: PayrollRunService,
          useValue: {
            list: (pageNumber: number) => {
              requestedPages.push(pageNumber);
              return listResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(RunList);
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

  it('asks for the first page on arrival', async () => {
    await createComponent();

    expect(requestedPages).toEqual([0]);
  });

  it('lists runs with their period, status and totals', async () => {
    await createComponent();

    expect(text()).toContain('August 2026');
    expect(text()).toContain('July 2026');
    expect(text()).toContain('Draft');
    expect(text()).toContain('Finalised');
    expect(text()).toContain('₹883,288,000.00');
  });

  it('offers Review for a draft and View for anything else', async () => {
    // A draft is opened to be acted on; a finalised month, to be read.
    await createComponent();

    const links = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.runs__link'),
    ).map((link) => link.textContent?.trim().split(/\s+/)[0]);
    expect(links).toEqual(['Review', 'View']);
  });

  it('links each row to that run', async () => {
    await createComponent();

    const first = (fixture.nativeElement as HTMLElement).querySelector('.runs__link');
    expect(first?.getAttribute('href')).toBe('/payroll-runs/7');
  });

  it('reserves the active badge for a published month', async () => {
    await createComponent();

    expect(component.statusClass('FINALISED')).toBe('badge--active');
    expect(component.statusClass('DRAFT')).toBe('badge--inactive');
    expect(component.statusClass('CANCELLED')).toBe('badge--inactive');
  });

  it('pages on the server rather than in the browser', async () => {
    listResult = () =>
      of(page([run()], { page: 0, totalElements: 30, totalPages: 2, hasNext: true }));
    await createComponent();

    component.load(1);
    await settle();

    expect(requestedPages).toEqual([0, 1]);
  });

  it('says so when no payroll has been run yet', async () => {
    // Not an error, and worth explaining: it means "not yet", not "something is wrong".
    listResult = () => of(emptyPage<PayrollRunSummary>());
    await createComponent();

    expect(text()).toContain('No payroll has been run yet');
  });

  it("shows the server's message on failure", async () => {
    listResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));
    await createComponent();

    expect(component.errorMessage()).toBe('Not permitted');
    expect(text()).toContain('Not permitted');
  });

  it('offers a way to start a run', async () => {
    await createComponent();

    const action = (fixture.nativeElement as HTMLElement).querySelector('.page-header__action');
    expect(action?.getAttribute('href')).toBe('/payroll-runs/new');
  });
});
