import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PayrollRunDetail, RunPayslip, StartPayrollRunRequest } from '../payroll-run.models';
import { PayrollRunService } from '../payroll-run.service';
import { StartRun } from './start-run';

function payslip(overrides: Partial<RunPayslip> = {}): RunPayslip {
  return {
    id: 1,
    employeeId: 1001,
    totalDays: 31,
    paidDays: 31,
    lopDays: 0,
    grossPay: '90000.00',
    totalDeductions: '6000.00',
    netPay: '84000.00',
    netPayInWords: 'Eighty Four Thousand Rupees Only',
    earnings: [],
    deductions: [],
    ...overrides,
  };
}

function draft(overrides: Partial<PayrollRunDetail['run']> = {}): PayrollRunDetail {
  return {
    run: {
      id: 7,
      periodYear: 2026,
      periodMonth: 8,
      period: '2026-08',
      status: 'DRAFT',
      employeeCount: 2,
      totalGross: '180000.00',
      totalDeductions: '12000.00',
      totalNet: '168000.00',
      createdAt: '2026-09-20T09:00:00Z',
      ...overrides,
    },
    payslips: [payslip(), payslip({ id: 2, employeeId: 1002, netPay: '84000.00' })],
  };
}

describe('StartRun', () => {
  let fixture: ComponentFixture<StartRun>;
  let component: StartRun;

  let requests: StartPayrollRunRequest[];
  let startResult: () => Observable<PayrollRunDetail>;

  beforeEach(() => {
    // Fixed so "the month just gone" is a fact rather than whatever today happens to be.
    // Only `Date` is faked: the timers the test harness itself uses must stay real.
    vi.useFakeTimers({ toFake: ['Date'], now: new Date(2026, 8, 20, 12) });

    requests = [];
    startResult = () => of(draft());

    TestBed.configureTestingModule({
      providers: [
        {
          provide: PayrollRunService,
          useValue: {
            start: (request: StartPayrollRunRequest) => {
              requests.push(request);
              return startResult();
            },
          },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(StartRun);
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

  function query<T extends HTMLElement>(selector: string): T {
    const element = (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
    if (!element) {
      throw new Error(`no element matching ${selector}`);
    }
    return element;
  }

  describe('choosing a period', () => {
    it('defaults to the month just gone', async () => {
      await createComponent();

      expect(component.form.getRawValue()).toEqual({ periodYear: 2026, periodMonth: 8 });
      expect(component.periodLabel()).toBe('August 2026');
    });

    it('offers no future year, because a future period can never be run', async () => {
      await createComponent();

      expect(component.years[0]).toBe(2026);
      expect(component.years).not.toContain(2027);
    });

    it('states the days the month is prorated over', async () => {
      await createComponent();

      // 31 for August, and the figure proration divides by (FR-5.4).
      expect(component.periodDays()).toBe(31);
      expect(text()).toContain('31 days');
    });

    it('puts a number in the control when the dropdown itself is used', async () => {
      // The `[ngValue]` decision, exercised through the DOM rather than through
      // patchValue — which is the only way it can fail. A plain `[value]` binding would
      // leave the string "7" here and send it to an Integer field.
      await createComponent();

      const month = query<HTMLSelectElement>('#periodMonth');
      month.selectedIndex = 6;
      month.dispatchEvent(new Event('change'));
      await settle();

      expect(component.form.controls.periodMonth.value).toBe(7);
      expect(component.periodLabel()).toBe('July 2026');
    });

    it('refuses a period that has not finished, without asking the server', async () => {
      await createComponent();

      component.form.patchValue({ periodMonth: 9 });
      await settle();

      expect(component.canStart()).toBe(false);
      expect(component.periodMessage()).toContain('has not finished yet');

      component.start();

      expect(requests).toEqual([]);
    });

    it('allows a month that ends today, as the server does', async () => {
      vi.setSystemTime(new Date(2026, 8, 30, 12));
      await createComponent();

      component.form.patchValue({ periodMonth: 9, periodYear: 2026 });
      await settle();

      expect(component.canStart()).toBe(true);
    });
  });

  describe('starting a run', () => {
    it('sends the selected period', async () => {
      await createComponent();

      component.start();
      await settle();

      expect(requests).toEqual([{ periodYear: 2026, periodMonth: 8 }]);
    });

    it('sends numbers, never the strings a select would otherwise bind', async () => {
      // The bug this guards: `[value]` on an option puts "8" in the control, and the
      // API's periodMonth is an Integer.
      await createComponent();

      component.form.patchValue({ periodMonth: 7 });
      await settle();
      component.start();
      await settle();

      expect(typeof requests[0].periodMonth).toBe('number');
      expect(typeof requests[0].periodYear).toBe('number');
      expect(requests[0].periodMonth).toBe(7);
    });

    it('ignores a second submit while one is in flight', async () => {
      // A run is one transaction over the whole payroll; a double-click must not start two.
      startResult = () => new Observable<PayrollRunDetail>(() => undefined);
      await createComponent();

      component.start();
      component.start();

      expect(requests).toHaveLength(1);
    });

    it('says the wait is expected, and for which period', async () => {
      startResult = () => new Observable<PayrollRunDetail>(() => undefined);
      await createComponent();

      component.start();
      await settle();

      expect(text()).toContain('Computing payroll for August 2026');
      expect(text()).toContain('up to a minute');
    });

    it('keeps naming the period it is computing if the dropdowns move', async () => {
      startResult = () => new Observable<PayrollRunDetail>(() => undefined);
      await createComponent();

      component.start();
      component.form.patchValue({ periodMonth: 7 });
      await settle();

      expect(component.runningFor()).toBe('August 2026');
    });
  });

  describe('the draft it produces', () => {
    it('shows the totals the server computed', async () => {
      await createComponent();

      component.start();
      await settle();

      expect(text()).toContain('₹180,000.00');
      expect(text()).toContain('₹12,000.00');
      expect(text()).toContain('₹168,000.00');
      expect(text()).toContain('DRAFT');
    });

    it('lists a row per computed payslip', async () => {
      await createComponent();

      component.start();
      await settle();

      expect(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.payslips tbody tr'),
      ).toHaveLength(2);
      expect(text()).toContain('#1001');
      expect(text()).toContain('2 payslips computed');
    });

    it('says nothing has been published', async () => {
      // The distinction the whole draft/finalise split exists for: these figures are not
      // yet visible to the employees they are about.
      await createComponent();

      component.start();
      await settle();

      expect(text()).toContain('Nothing has been published');
    });

    it("names the run's own period, not whatever the dropdowns now say", async () => {
      await createComponent();

      component.start();
      await settle();
      component.form.patchValue({ periodMonth: 3 });
      await settle();

      // A heading that followed the form would relabel a draft already computed. The form
      // itself has moved on to March, which is why this asserts on the heading and not on
      // the page's text.
      const heading = (fixture.nativeElement as HTMLElement).querySelector('.result__period');
      expect(heading?.textContent).toContain('August 2026');
      expect(heading?.textContent).not.toContain('March');
    });

    it('clears a previous draft when another run is started', async () => {
      await createComponent();

      component.start();
      await settle();
      startResult = () => new Observable<PayrollRunDetail>(() => undefined);
      component.form.patchValue({ periodMonth: 7 });
      await settle();
      component.start();
      await settle();

      expect(component.started()).toBeNull();
      expect(text()).not.toContain('₹168,000.00');
    });
  });

  describe('when the server refuses', () => {
    it('shows the 409 for a period that already has a run', async () => {
      startResult = () =>
        throwError(
          () =>
            new ApiFailure(
              409,
              'a payroll run for 2026-08 already exists; cancel it to run again',
            ),
        );
      await createComponent();

      component.start();
      await settle();

      expect(component.generalFailure()).toContain('already exists');
      expect(text()).toContain('cancel it to run again');
    });

    it('shows the 409 for a period nobody is payable in', async () => {
      startResult = () =>
        throwError(
          () =>
            new ApiFailure(
              409,
              'no employee is payable for 2026-08; nobody eligible holds a salary structure'
                + ' effective in that period',
            ),
        );
      await createComponent();

      component.start();
      await settle();

      expect(text()).toContain('no employee is payable');
    });

    it("prefers the server's message about the period to its own", async () => {
      // The browser's clock is not authoritative — if the server says the period is not
      // over, that is the message worth showing.
      startResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Validation failed', [
              {
                field: 'periodMonth',
                message: '2026-08 has not finished yet; payroll can only be run for a'
                  + ' completed period',
              },
            ]),
        );
      await createComponent();

      component.start();
      await settle();

      expect(component.periodMessage()).toContain('payroll can only be run for a completed');
      // A field error belongs under the control, not in the page-level alert as well.
      expect(component.generalFailure()).toBeNull();
    });

    it('forgets a refusal once a different period is chosen', async () => {
      // Otherwise "2026-08 already has a run" would sit under a dropdown now reading July.
      startResult = () => throwError(() => new ApiFailure(409, 'a payroll run for 2026-08 already exists'));
      await createComponent();

      component.start();
      await settle();
      expect(component.generalFailure()).not.toBeNull();

      component.form.patchValue({ periodMonth: 7 });
      await settle();

      expect(component.generalFailure()).toBeNull();
      expect(text()).not.toContain('already exists');
    });

    it('leaves no half-finished draft on screen', async () => {
      startResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));
      await createComponent();

      component.start();
      await settle();

      expect(component.started()).toBeNull();
      expect(component.starting()).toBe(false);
      expect(text()).toContain('Not permitted');
    });

    it('lets the run be retried after a failure', async () => {
      startResult = () => throwError(() => new ApiFailure(0, 'Could not reach the API'));
      await createComponent();

      component.start();
      await settle();
      startResult = () => of(draft());
      component.start();
      await settle();

      expect(requests).toHaveLength(2);
      expect(component.started()).not.toBeNull();
      expect(component.generalFailure()).toBeNull();
    });
  });
});
