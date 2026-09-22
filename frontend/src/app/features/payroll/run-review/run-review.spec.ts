import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import {
  PayrollRunDetail,
  PayrollRunStatus,
  RecomputePayrollRunRequest,
  RunPayslip,
} from '../payroll-run.models';
import { PayrollRunService } from '../payroll-run.service';
import { RunReview } from './run-review';

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

function detail(
  status: PayrollRunStatus = 'DRAFT',
  payslips: RunPayslip[] = [payslip(), payslip({ id: 2, employeeId: 1002 })],
): PayrollRunDetail {
  return {
    run: {
      id: 7,
      periodYear: 2026,
      periodMonth: 8,
      period: '2026-08',
      status,
      employeeCount: payslips.length,
      totalGross: '180000.00',
      totalDeductions: '12000.00',
      totalNet: '168000.00',
      createdAt: '2026-09-01T09:00:00Z',
    },
    payslips,
  };
}

/** Thirty rows, so the browser-side paging of the table has something to page. */
function manyPayslips(count: number): RunPayslip[] {
  return Array.from({ length: count }, (_, index) =>
    payslip({ id: index + 1, employeeId: 2000 + index }),
  );
}

describe('RunReview', () => {
  let fixture: ComponentFixture<RunReview>;
  let component: RunReview;

  let getIds: number[];
  let recomputeCalls: { id: number; request: RecomputePayrollRunRequest }[];
  let finaliseCalls: number[];
  let cancelCalls: number[];

  let getResult: () => Observable<PayrollRunDetail>;
  let recomputeResult: () => Observable<PayrollRunDetail>;
  let finaliseResult: () => Observable<PayrollRunDetail>;
  let cancelResult: () => Observable<PayrollRunDetail>;

  beforeEach(() => {
    getIds = [];
    recomputeCalls = [];
    finaliseCalls = [];
    cancelCalls = [];

    getResult = () => of(detail());
    recomputeResult = () =>
      of(
        detail('DRAFT', [
          payslip({ lopDays: 3, paidDays: 28, netPay: '75870.97' }),
          payslip({ id: 2, employeeId: 1002 }),
        ]),
      );
    finaliseResult = () => of(detail('FINALISED'));
    cancelResult = () => of(detail('CANCELLED'));

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: PayrollRunService,
          useValue: {
            get: (id: number) => {
              getIds.push(id);
              return getResult();
            },
            recompute: (id: number, request: RecomputePayrollRunRequest) => {
              recomputeCalls.push({ id, request });
              return recomputeResult();
            },
            finalise: (id: number) => {
              finaliseCalls.push(id);
              return finaliseResult();
            },
            cancel: (id: number) => {
              cancelCalls.push(id);
              return cancelResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(id = '7'): Promise<void> {
    fixture = TestBed.createComponent(RunReview);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', id);
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

  function rows(): NodeListOf<HTMLElement> {
    return (fixture.nativeElement as HTMLElement).querySelectorAll('.payslips tbody tr');
  }

  describe('reading a draft', () => {
    it('requests the run named in the route', async () => {
      await createComponent('7');

      expect(getIds).toEqual([7]);
    });

    it('shows the period, status and totals', async () => {
      await createComponent();

      expect(text()).toContain('August 2026');
      expect(text()).toContain('DRAFT');
      expect(text()).toContain('₹180,000.00');
      expect(text()).toContain('₹168,000.00');
    });

    it('says the figures are what finalising would publish', async () => {
      // FR-5.8: a run is never recomputed at finalisation, which is the only thing that
      // makes reviewing it meaningful.
      await createComponent();

      expect(text()).toContain('never recomputed at finalisation');
    });

    it('lists a row per payslip', async () => {
      await createComponent();

      expect(rows()).toHaveLength(2);
      expect(text()).toContain('#1001');
      expect(text()).toContain('#1002');
    });

    it('rejects a route id that is not a valid reference, without requesting it', async () => {
      await createComponent('abc');

      expect(getIds).toEqual([]);
      expect(component.loadError()).toContain('not a valid payroll run reference');
    });

    it("shows the server's message when the run cannot be loaded", async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Payroll run 42 was not found'));

      await createComponent('42');

      expect(component.loadError()).toBe('Payroll run 42 was not found');
      expect(text()).toContain('Payroll run 42 was not found');
    });
  });

  describe('loss-of-pay adjustments', () => {
    it('seeds the edits from what the run already holds', async () => {
      getResult = () =>
        of(detail('DRAFT', [payslip({ lopDays: 2, paidDays: 29 }), payslip({ id: 2, employeeId: 1002 })]));
      await createComponent();

      expect(component.lopFor(1001)).toBe(2);
      expect(component.lopFor(1002)).toBe(0);
    });

    it('will not recompute until something has changed', async () => {
      await createComponent();

      expect(component.hasPendingEdits()).toBe(false);

      component.setLop(1001, '3', 31);
      await settle();

      expect(component.hasPendingEdits()).toBe(true);
    });

    it('sends the complete picture, not a delta', async () => {
      // FR-5.6: an employee absent from the list is recomputed at full attendance, which
      // is how a mistaken entry is undone.
      getResult = () =>
        of(
          detail('DRAFT', [
            payslip({ lopDays: 2, paidDays: 29 }),
            payslip({ id: 2, employeeId: 1002, lopDays: 4, paidDays: 27 }),
          ]),
        );
      await createComponent();

      // Clear one, change the other.
      component.setLop(1001, '0', 31);
      component.setLop(1002, '5', 31);
      component.recompute();
      await settle();

      expect(recomputeCalls).toEqual([
        { id: 7, request: { adjustments: [{ employeeId: 1002, lopDays: 5 }] } },
      ]);
    });

    it('omits zero rather than sending it, because absent means full attendance', async () => {
      await createComponent();

      component.setLop(1001, '3', 31);
      component.setLop(1001, '0', 31);
      component.recompute();
      await settle();

      expect(recomputeCalls[0].request.adjustments).toEqual([]);
    });

    it('clamps an impossible number of days to the month', async () => {
      // The server refuses more days than the month has; an input that cannot express an
      // impossible number is kinder than an error about one.
      await createComponent();

      component.setLop(1001, '99', 31);

      expect(component.lopFor(1001)).toBe(31);

      component.setLop(1001, '-4', 31);

      expect(component.lopFor(1001)).toBe(0);
    });

    it('adopts the recomputed run and re-seeds the edits from it', async () => {
      await createComponent();

      component.setLop(1001, '3', 31);
      component.recompute();
      await settle();

      expect(component.lopFor(1001)).toBe(3);
      expect(component.hasPendingEdits()).toBe(false);
      expect(text()).toContain('Recomputed');
    });

    it('shows the field error naming the employee the server objected to', async () => {
      recomputeResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Loss-of-pay adjustments are not valid', [
              {
                field: 'adjustments',
                message: 'employee 9999 is not included in this run',
              },
            ]),
        );
      await createComponent();

      component.setLop(1001, '3', 31);
      component.recompute();
      await settle();

      expect(component.actionError()).toContain('employee 9999 is not included');
      expect(text()).toContain('employee 9999 is not included');
    });
  });

  describe('finalising', () => {
    it('takes two clicks, and says what the first one will do', async () => {
      await createComponent();

      component.confirm('finalise');
      await settle();

      expect(finaliseCalls).toEqual([]);
      expect(text()).toContain('Publish August 2026?');
      expect(text()).toContain('cannot be undone');
    });

    it('publishes on the second click', async () => {
      await createComponent();

      component.confirm('finalise');
      component.finalise();
      await settle();

      expect(finaliseCalls).toEqual([7]);
      expect(text()).toContain('FINALISED');
      expect(text()).toContain('visible to the employees');
    });

    it('can be backed out of', async () => {
      await createComponent();

      component.confirm('finalise');
      component.dismissConfirmation();
      await settle();

      expect(finaliseCalls).toEqual([]);
      expect(text()).not.toContain('Publish August 2026?');
    });

    it('stops offering the actions once the run is published', async () => {
      await createComponent();

      component.confirm('finalise');
      component.finalise();
      await settle();

      expect(component.isDraft()).toBe(false);
      expect(text()).not.toContain('Finalise and publish');
      expect(text()).not.toContain('Cancel run');
    });

    it("shows the server's refusal for a run that is no longer a draft", async () => {
      finaliseResult = () =>
        throwError(() => new ApiFailure(409, 'Payroll run 7 is FINALISED and cannot be changed'));
      await createComponent();

      component.confirm('finalise');
      component.finalise();
      await settle();

      expect(component.actionError()).toContain('cannot be changed');
      expect(text()).toContain('cannot be changed');
    });

    it('ignores a second click while one is in flight', async () => {
      finaliseResult = () => new Observable<PayrollRunDetail>(() => undefined);
      await createComponent();

      component.confirm('finalise');
      component.finalise();
      component.finalise();

      expect(finaliseCalls).toHaveLength(1);
    });
  });

  describe('cancelling', () => {
    it('also takes two clicks', async () => {
      await createComponent();

      component.confirm('cancel');
      await settle();

      expect(cancelCalls).toEqual([]);
      expect(text()).toContain('Abandon this draft?');

      component.cancelRun();
      await settle();

      expect(cancelCalls).toEqual([7]);
      expect(text()).toContain('CANCELLED');
      expect(text()).toContain('free to be run again');
    });
  });

  describe('a run that is not a draft', () => {
    it('shows the days as read-only figures rather than inputs', async () => {
      getResult = () => of(detail('FINALISED'));
      await createComponent();

      expect(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.payslips__lop'),
      ).toHaveLength(0);
      expect(text()).not.toContain('Recompute');
    });

    it('explains that a finalised month is immutable', async () => {
      getResult = () => of(detail('FINALISED'));
      await createComponent();

      expect(text()).toContain('Published and immutable');
    });

    it('explains that a cancelled draft freed its period', async () => {
      getResult = () => of(detail('CANCELLED'));
      await createComponent();

      expect(text()).toContain('free to be run again');
    });
  });

  describe('the payslip table at scale', () => {
    it('keeps a thousand rows out of the DOM by paging in the browser', async () => {
      // A stopgap until the API pages payslips, and the screen says so rather than
      // pretending the payload is small.
      getResult = () => of(detail('DRAFT', manyPayslips(60)));
      await createComponent();

      expect(rows()).toHaveLength(25);
      expect(component.totalRowPages()).toBe(3);
      expect(text()).toContain('Showing 1–25 of 60');
      expect(text()).toContain('paged payslip endpoint is still outstanding');
    });

    it('moves through the pages', async () => {
      getResult = () => of(detail('DRAFT', manyPayslips(60)));
      await createComponent();

      component.showRowPage(2);
      await settle();

      expect(text()).toContain('Showing 51–60 of 60');
      expect(rows()).toHaveLength(10);
    });

    it('will not page past either end', async () => {
      getResult = () => of(detail('DRAFT', manyPayslips(60)));
      await createComponent();

      component.showRowPage(99);
      expect(component.rowsPage()).toBe(2);

      component.showRowPage(-5);
      expect(component.rowsPage()).toBe(0);
    });

    it('keeps an edit made on a later page', async () => {
      getResult = () => of(detail('DRAFT', manyPayslips(60)));
      await createComponent();

      component.showRowPage(1);
      component.setLop(2030, '2', 31);
      component.showRowPage(0);
      await settle();

      expect(component.lopFor(2030)).toBe(2);
      expect(component.hasPendingEdits()).toBe(true);
    });
  });
});
