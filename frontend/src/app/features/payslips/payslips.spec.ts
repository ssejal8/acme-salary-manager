import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../core/http/api-error';
import { formatPeriod } from '../../shared/dates';
import { MyPayslips } from './my-payslips/my-payslips';
import { Payslip } from './payslip.models';
import { PayslipService } from './payslip.service';
import { PayslipView } from './payslip-view/payslip-view';

/**
 * The employee payslip screens.
 *
 * Figures follow employee `E-1001`'s seeded package, with a five-day loss of pay applied,
 * so the prorated numbers are checkable against the dev data rather than invented.
 */
function payslip(overrides: Partial<Payslip> = {}): Payslip {
  return {
    id: 90,
    periodYear: 2026,
    periodMonth: 4,
    period: '2026-04',
    runStatus: 'FINALISED',
    published: true,
    publishedAt: '2026-05-01T10:00:00Z',
    employee: {
      id: 1001,
      employeeCode: 'E-1001',
      fullName: 'Asha Menon',
      workEmail: 'asha.menon@acme.test',
      department: 'Engineering',
      designation: 'Senior Software Engineer',
    },
    totalDays: 30,
    paidDays: 25,
    lopDays: 5,
    grossPay: '125000.00',
    totalDeductions: '7700.00',
    netPay: '117300.00',
    netPayInWords: 'One Lakh Seventeen Thousand Three Hundred Rupees Only',
    earnings: [
      { code: 'BASIC', name: 'Basic Salary', type: 'EARNING', amount: '62500.00' },
      { code: 'HRA', name: 'House Rent Allowance', type: 'EARNING', amount: '25000.00' },
    ],
    deductions: [
      { code: 'PF', name: 'Provident Fund', type: 'DEDUCTION', amount: '7500.00' },
      { code: 'PROF_TAX', name: 'Professional Tax', type: 'DEDUCTION', amount: '200.00' },
    ],
    ...overrides,
  };
}

describe('PayslipService', () => {
  let service: PayslipService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PayslipService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('asks for the caller\'s own payslips without naming an employee', () => {
    // The whole point of /me: nothing in the request could aim it at somebody else.
    service.mine().subscribe();

    const request = backend.expectOne('/api/v1/payslips/me');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys()).toEqual([]);
    request.flush([]);
  });

  it('fetches one payslip by id', () => {
    service.get(90).subscribe();

    backend.expectOne('/api/v1/payslips/90').flush(payslip());
  });
});

describe('MyPayslips', () => {
  let fixture: ComponentFixture<MyPayslips>;
  let component: MyPayslips;
  let mineResult: () => Observable<Payslip[]>;

  beforeEach(() => {
    mineResult = () => of([payslip()]);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: PayslipService, useValue: { mine: () => mineResult() } },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(MyPayslips);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function queryAll(selector: string): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(selector));
  }

  it('shows the most recent payslip in full', async () => {
    // The list response carries every figure, so the common case needs no second click.
    await createComponent();

    expect(component.latest()?.id).toBe(90);
    expect(text()).toContain('April 2026');
    expect(text()).toContain('₹125,000.00');
    expect(text()).toContain('₹117,300.00');
    expect(text()).toContain('25 / 30');
  });

  it('lists earlier payslips as rows, newest first as the API ordered them', async () => {
    mineResult = () =>
      of([
        payslip({ id: 90, periodMonth: 4 }),
        payslip({ id: 89, periodMonth: 3, netPay: '140800.00' }),
        payslip({ id: 88, periodMonth: 2, netPay: '140800.00' }),
      ]);
    await createComponent();

    expect(component.latest()?.id).toBe(90);
    expect(component.earlier().map((p) => p.id)).toEqual([89, 88]);
    expect(queryAll('tbody tr')).toHaveLength(2);
    expect(text()).toContain('March 2026');
    expect(text()).toContain('February 2026');
  });

  it('says nothing about earlier payslips when there is only one', async () => {
    await createComponent();

    expect(component.earlier()).toEqual([]);
    expect(text()).not.toContain('Earlier payslips');
  });

  it('explains unpaid leave rather than leaving a smaller month unexplained', async () => {
    await createComponent();

    expect(text()).toContain('5 days of unpaid leave');
  });

  it('says nothing about unpaid leave when there was none', async () => {
    mineResult = () => of([payslip({ lopDays: 0, paidDays: 30 })]);
    await createComponent();

    expect(text()).not.toContain('unpaid leave');
  });

  it('reads correctly for a single unpaid day', async () => {
    mineResult = () => of([payslip({ lopDays: 1, paidDays: 29 })]);
    await createComponent();

    expect(text()).toContain('1 day of unpaid leave');
  });

  describe('when there are none', () => {
    beforeEach(() => {
      mineResult = () => of([]);
    });

    it('says why rather than showing a bare empty list', async () => {
      // Almost always "not yet" rather than "something is wrong".
      await createComponent();

      expect(component.hasNone()).toBe(true);
      expect(text()).toContain('no payslips yet');
      expect(text()).toContain('finalised');
    });

    it('does not claim there are none before the response arrives', async () => {
      mineResult = () => new Observable<Payslip[]>(() => undefined);
      fixture = TestBed.createComponent(MyPayslips);
      component = fixture.componentInstance;
      await fixture.whenStable();
      fixture.detectChanges();

      expect(component.loaded()).toBe(false);
      expect(component.hasNone()).toBe(false);
      expect(text()).not.toContain('no payslips yet');
    });
  });

  describe('when they cannot be loaded', () => {
    it('shows the server\'s message and announces it', async () => {
      mineResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();

      expect(component.errorMessage()).toBe('Something went wrong');
      expect(queryAll('[role="alert"]').length).toBeGreaterThan(0);
    });

    it('does not also claim there are none', async () => {
      mineResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();

      expect(component.hasNone()).toBe(false);
      expect(text()).not.toContain('no payslips yet');
    });

    it('falls back to a generic message for a failure it cannot read', async () => {
      mineResult = () => throwError(() => new Error('socket hang up'));
      await createComponent();

      expect(component.errorMessage()).toBe('Your payslips could not be loaded.');
    });
  });

  it('links each payslip to its own page', async () => {
    mineResult = () => of([payslip({ id: 90 }), payslip({ id: 89, periodMonth: 3 })]);
    await createComponent();

    const links = queryAll('a[href]').map((a) => a.getAttribute('href'));
    expect(links).toContain('/payslips/90');
    expect(links).toContain('/payslips/89');
  });
});

describe('PayslipView', () => {
  let fixture: ComponentFixture<PayslipView>;
  let component: PayslipView;
  let requestedIds: number[];
  let getResult: () => Observable<Payslip>;

  beforeEach(() => {
    requestedIds = [];
    getResult = () => of(payslip());

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: PayslipService,
          useValue: {
            get: (id: number) => {
              requestedIds.push(id);
              return getResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(id = '90'): Promise<void> {
    fixture = TestBed.createComponent(PayslipView);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', id);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('fetches by id, so a pasted link works without visiting the list', async () => {
    await createComponent('90');

    expect(requestedIds).toEqual([90]);
  });

  it('identifies the employee and the period', async () => {
    // FR-6.2: a payslip has to stand alone.
    await createComponent();

    expect(text()).toContain('Asha Menon');
    expect(text()).toContain('E-1001');
    expect(text()).toContain('Senior Software Engineer');
    expect(text()).toContain('Engineering');
    expect(text()).toContain('April 2026');
  });

  it('shows attendance', async () => {
    await createComponent();

    expect(text()).toContain('25 of 30');
    expect(text()).toContain('5 unpaid');
  });

  it('lists every earning and deduction with its own subtotal', async () => {
    await createComponent();

    expect(text()).toContain('Basic Salary');
    expect(text()).toContain('House Rent Allowance');
    expect(text()).toContain('Provident Fund');
    expect(text()).toContain('Professional Tax');
    // The server's totals, shown beside the lines that make them up.
    expect(text()).toContain('₹125,000.00');
    expect(text()).toContain('₹7,700.00');
  });

  it('shows net pay and the words for it', async () => {
    // FR-6.3, rendered by the server so the two cannot disagree.
    await createComponent();

    expect(text()).toContain('₹117,300.00');
    expect(text()).toContain('One Lakh Seventeen Thousand Three Hundred Rupees Only');
  });

  it('explains proration when there was unpaid leave', async () => {
    await createComponent();

    expect(text()).toContain('earnings are prorated to 25 of 30 days');
    // The asymmetry is worth stating: a flat statutory deduction does not shrink.
    expect(text()).toContain('not prorated');
  });

  it('marks a draft payslip as not yet published', async () => {
    // Only HR ever reaches one — an employee is refused a draft — but whoever sees it
    // must know the figures are not final.
    getResult = () =>
      of(payslip({ published: false, runStatus: 'DRAFT', publishedAt: undefined }));
    await createComponent();

    expect(text()).toContain('not yet published');
  });

  it('marks a published payslip as published', async () => {
    await createComponent();

    expect(text()).toContain('Published');
  });

  it('refetches when pointed at another payslip', async () => {
    await createComponent('90');

    fixture.componentRef.setInput('id', '89');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(requestedIds).toEqual([90, 89]);
  });

  describe('a payslip that is not available', () => {
    it('shows the server\'s message without guessing why', async () => {
      // The API answers 404 for a payslip the caller may not have, so that it cannot be
      // used to confirm an id exists. This screen must not try to distinguish the cases
      // either.
      getResult = () => throwError(() => new ApiFailure(404, 'Payslip 90 was not found'));
      await createComponent();

      expect(component.errorMessage()).toBe('Payslip 90 was not found');
      expect(text()).not.toContain('permission');
      expect(text()).not.toContain('not yours');
    });

    it('offers the way back to the list', async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Payslip 90 was not found'));
      await createComponent();

      expect(text()).toContain('Back to my payslips');
    });

    it('rejects a reference that is not a valid id without requesting it', async () => {
      await createComponent('abc');

      expect(requestedIds).toEqual([]);
      expect(component.errorMessage()).toContain('not a valid payslip reference');
    });

    it('renders no figures alongside the error', async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Payslip 90 was not found'));
      await createComponent();

      expect(component.payslip()).toBeNull();
      expect(text()).not.toContain('Asha Menon');
    });
  });
});

describe('period formatting', () => {
  it('reads as a month and year rather than the API\'s compact form', () => {
    expect(formatPeriod(2026, 4)).toBe('April 2026');
    expect(formatPeriod(2026, 1)).toBe('January 2026');
    expect(formatPeriod(2026, 12)).toBe('December 2026');
  });

  it('falls back to the compact form for a month outside the calendar', () => {
    expect(formatPeriod(2026, 0)).toBe('2026-00');
    expect(formatPeriod(2026, 13)).toBe('2026-13');
  });
});
