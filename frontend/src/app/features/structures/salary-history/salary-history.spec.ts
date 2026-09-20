import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { CURRENCY_SYMBOL } from '../../../shared/money';
import { SalaryStructure } from '../salary-structure.models';
import { SalaryStructureService } from '../salary-structure.service';
import { SalaryHistory } from './salary-history';

/**
 * Fixtures use employee `E-1001`'s real seeded packages, figures included: a G2-level
 * package superseded by a G3-level one after a raise. Inventing round numbers would have
 * been easier and would not have exercised a percentage deduction against two different
 * basics.
 */

/** The package in force: 150,000 gross, PF at 12% of a 75,000 basic. */
function currentRevision(overrides: Partial<SalaryStructure> = {}): SalaryStructure {
  return {
    id: 5002,
    employeeId: 1001,
    effectiveFrom: '2025-04-01',
    current: true,
    createdAt: '2025-03-20T09:30:00Z',
    earnings: [
      earning(1, 'BASIC', 'Basic Salary', '75000.00'),
      earning(2, 'HRA', 'House Rent Allowance', '30000.00'),
      earning(3, 'CONVEYANCE', 'Conveyance Allowance', '2000.00'),
      earning(4, 'SPECIAL', 'Special Allowance', '43000.00'),
    ],
    deductions: [
      percentDeduction(5, 'PF', 'Provident Fund', '12.00', '9000.00'),
      flatDeduction(6, 'PROF_TAX', 'Professional Tax', '200.00'),
    ],
    totals: {
      basicMonthly: '75000.00',
      grossMonthly: '150000.00',
      totalDeductions: '9200.00',
      netMonthly: '140800.00',
      annualCtc: '1800000.00',
    },
    ...overrides,
  };
}

/** The revision it replaced: 90,000 gross, the same 12% against a 45,000 basic. */
function supersededRevision(overrides: Partial<SalaryStructure> = {}): SalaryStructure {
  return {
    id: 5001,
    employeeId: 1001,
    effectiveFrom: '2022-06-01',
    supersededOn: '2025-04-01',
    current: false,
    createdAt: '2022-05-25T11:00:00Z',
    earnings: [
      earning(1, 'BASIC', 'Basic Salary', '45000.00'),
      earning(2, 'HRA', 'House Rent Allowance', '18000.00'),
      earning(3, 'CONVEYANCE', 'Conveyance Allowance', '2000.00'),
      earning(4, 'SPECIAL', 'Special Allowance', '25000.00'),
    ],
    deductions: [
      percentDeduction(5, 'PF', 'Provident Fund', '12.00', '5400.00'),
      flatDeduction(6, 'PROF_TAX', 'Professional Tax', '200.00'),
    ],
    totals: {
      basicMonthly: '45000.00',
      grossMonthly: '90000.00',
      totalDeductions: '5600.00',
      netMonthly: '84400.00',
      annualCtc: '1080000.00',
    },
    ...overrides,
  };
}

function earning(componentId: number, code: string, name: string, amount: string) {
  return {
    componentId,
    code,
    name,
    type: 'EARNING' as const,
    calculationType: 'FLAT' as const,
    configuredValue: amount,
    monthlyAmount: amount,
  };
}

function flatDeduction(componentId: number, code: string, name: string, amount: string) {
  return {
    componentId,
    code,
    name,
    type: 'DEDUCTION' as const,
    calculationType: 'FLAT' as const,
    configuredValue: amount,
    monthlyAmount: amount,
  };
}

/** `configuredValue` is a percentage here, `monthlyAmount` what it came to. */
function percentDeduction(
  componentId: number,
  code: string,
  name: string,
  rate: string,
  amount: string,
) {
  return {
    componentId,
    code,
    name,
    type: 'DEDUCTION' as const,
    calculationType: 'PERCENT_OF_BASIC' as const,
    configuredValue: rate,
    monthlyAmount: amount,
  };
}

describe('SalaryHistory', () => {
  let fixture: ComponentFixture<SalaryHistory>;
  let component: SalaryHistory;

  let requestedIds: number[];
  let historyResult: () => Observable<SalaryStructure[]>;

  beforeEach(() => {
    requestedIds = [];
    historyResult = () => of([currentRevision(), supersededRevision()]);

    TestBed.configureTestingModule({
      providers: [
        // The screen links to the assignment form, so routerLink needs a real router.
        provideRouter([]),
        {
          provide: SalaryStructureService,
          useValue: {
            history: (employeeId: number) => {
              requestedIds.push(employeeId);
              return historyResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(employeeId = 1001): Promise<void> {
    fixture = TestBed.createComponent(SalaryHistory);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('employeeId', employeeId);
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

  it('asks for the history of the employee it was given', async () => {
    await createComponent(1001);

    expect(requestedIds).toEqual([1001]);
  });

  it('refetches when it is pointed at another employee', async () => {
    await createComponent(1001);

    fixture.componentRef.setInput('employeeId', 1002);
    await settle();

    expect(requestedIds).toEqual([1001, 1002]);
  });

  describe('the package in force', () => {
    it('is taken from the server\'s current flag', async () => {
      await createComponent();

      expect(component.current()?.id).toBe(5002);
      expect(component.superseded().map((revision) => revision.id)).toEqual([5001]);
    });

    it('shows its totals', async () => {
      await createComponent();

      expect(text()).toContain('₹75,000.00');
      expect(text()).toContain('₹150,000.00');
      expect(text()).toContain('₹9,200.00');
      expect(text()).toContain('₹140,800.00');
      // Grouped in threes, not the Indian lakh convention — matching the figures the
      // project README publishes.
      expect(text()).toContain('₹1,800,000.00');
    });

    it('shows when it took effect, and marks it as in force', async () => {
      await createComponent();

      expect(text()).toContain('Effective from 1 Apr 2025');
      expect(queryAll('.badge--active')).toHaveLength(1);
      expect(text()).toContain('In force');
    });

    it('lists every earning and deduction with its code', async () => {
      await createComponent();

      for (const name of [
        'Basic Salary',
        'House Rent Allowance',
        'Conveyance Allowance',
        'Special Allowance',
        'Provident Fund',
        'Professional Tax',
      ]) {
        expect(text()).toContain(name);
      }
      expect(text()).toContain('BASIC');
      expect(text()).toContain('PROF_TAX');
    });

    it('carries a subtotal on each table, so the columns can be checked by hand', async () => {
      // Components are rounded before summing (NFR-3.3), which is what makes the lines
      // add up to the total exactly. Showing both is what makes that verifiable.
      await createComponent();

      expect(text()).toContain('Gross monthly');
      expect(text()).toContain('Total deductions');
    });
  });

  /**
   * The reason the API sends both `configuredValue` and `monthlyAmount`: for a percentage
   * component they differ, and a reviewer needs to see the 12% as well as the ₹9,000.
   */
  describe('how a component is calculated', () => {
    it('shows the rate and the amount for a percentage component', async () => {
      await createComponent();

      expect(text()).toContain('12% of basic');
      expect(text()).toContain('₹9,000.00');
    });

    it('never renders a rate as money', async () => {
      // The bug this guards: 12.00 through the money formatter reads as ₹12.00, for what
      // is actually a ₹9,000 deduction.
      await createComponent();

      const basis = queryAll('.lines__basis').map((cell) => cell.textContent ?? '');
      const percentageCells = basis.filter((cell) => cell.includes('%'));

      expect(percentageCells.length).toBeGreaterThan(0);
      for (const cell of percentageCells) {
        expect(cell).not.toContain(CURRENCY_SYMBOL);
      }
    });

    it('describes a flat component without repeating its figure', async () => {
      await createComponent();

      expect(text()).toContain('Fixed monthly');
    });

    it('applies the same rate to each revision\'s own basic', async () => {
      // 12% of 75,000 and 12% of 45,000 are different amounts from the same rate, which
      // is exactly why the amount is the server's to compute and not the client's.
      await createComponent();
      component.toggle(5001);
      await settle();

      expect(text()).toContain('₹9,000.00');
      expect(text()).toContain('₹5,400.00');
    });
  });

  describe('earlier revisions', () => {
    it('shows the period each one governed', async () => {
      await createComponent();

      expect(text()).toContain('1 Jun 2022');
      expect(text()).toContain('1 Apr 2025');
    });

    it('summarises them without expanding', async () => {
      await createComponent();

      expect(text()).toContain('₹90,000.00');
      expect(text()).toContain('₹84,400.00');
    });

    it('explains that revisions supersede rather than overwrite', async () => {
      // Otherwise "earlier revisions" reads as a changelog rather than as the figures
      // payroll for those months actually used.
      await createComponent();

      expect(text()).toContain('superseded rather than overwritten');
    });

    it('keeps the breakdown collapsed until asked', async () => {
      await createComponent();

      expect(component.isExpanded(5001)).toBe(false);
      // The superseded revision's own basic is not on screen yet.
      expect(text()).not.toContain('₹45,000.00');
    });

    it('expands and collapses on demand', async () => {
      await createComponent();

      component.toggle(5001);
      await settle();
      expect(component.isExpanded(5001)).toBe(true);
      expect(text()).toContain('₹45,000.00');

      component.toggle(5001);
      await settle();
      expect(component.isExpanded(5001)).toBe(false);
      expect(text()).not.toContain('₹45,000.00');
    });

    it('announces the expansion state rather than only showing it', async () => {
      await createComponent();
      const toggle = queryAll('button[aria-controls]')[0];

      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(toggle.getAttribute('aria-controls')).toBe('revision-5001');

      component.toggle(5001);
      await settle();

      expect(queryAll('button[aria-controls]')[0].getAttribute('aria-expanded')).toBe('true');
    });

    it('tracks each revision independently', async () => {
      historyResult = () =>
        of([
          currentRevision(),
          supersededRevision(),
          supersededRevision({ id: 4999, effectiveFrom: '2021-01-01', supersededOn: '2022-06-01' }),
        ]);
      await createComponent();

      component.toggle(4999);
      await settle();

      expect(component.isExpanded(4999)).toBe(true);
      expect(component.isExpanded(5001)).toBe(false);
    });

    it('says nothing about earlier revisions when there is only one package', async () => {
      historyResult = () => of([currentRevision()]);
      await createComponent();

      expect(component.superseded()).toEqual([]);
      expect(text()).not.toContain('Earlier revisions');
    });
  });

  /**
   * An override reason is present only where the package was accepted outside the
   * employee's grade CTC band (FR-4.3). Its presence is the signal, so it is stated.
   */
  describe('a package approved outside its grade band', () => {
    it('shows the recorded reason', async () => {
      historyResult = () =>
        of([currentRevision({ overrideReason: 'Retention case approved by the CFO' })]);
      await createComponent();

      expect(text()).toContain('Approved outside the grade band');
      expect(text()).toContain('Retention case approved by the CFO');
      expect(queryAll('.alert--warning')).toHaveLength(1);
    });

    it('says nothing when the package sits inside the band', async () => {
      await createComponent();

      expect(text()).not.toContain('Approved outside the grade band');
      expect(queryAll('.alert--warning')).toHaveLength(0);
    });

    it('shows the reason for an earlier revision too, once expanded', async () => {
      historyResult = () =>
        of([
          currentRevision(),
          supersededRevision({ overrideReason: 'Approved on joining' }),
        ]);
      await createComponent();

      expect(text()).not.toContain('Approved on joining');

      component.toggle(5001);
      await settle();

      expect(text()).toContain('Approved on joining');
    });
  });

  describe('an employee with no package', () => {
    beforeEach(() => {
      historyResult = () => of([]);
    });

    it('says so, as a gap to fill rather than a fault', async () => {
      await createComponent();

      expect(component.hasNoPackage()).toBe(true);
      expect(text()).toContain('No compensation package assigned');
      expect(text()).toContain('coverage gap');
    });

    it('shows no package and no revisions', async () => {
      await createComponent();

      expect(component.current()).toBeNull();
      expect(component.superseded()).toEqual([]);
      expect(text()).not.toContain('Current package');
    });

    it('does not claim there is no package before the response arrives', async () => {
      historyResult = () => new Observable<SalaryStructure[]>(() => undefined);
      fixture = TestBed.createComponent(SalaryHistory);
      component = fixture.componentInstance;
      fixture.componentRef.setInput('employeeId', 1010);
      await fixture.whenStable();
      fixture.detectChanges();

      expect(component.loaded()).toBe(false);
      expect(component.hasNoPackage()).toBe(false);
      expect(text()).not.toContain('No compensation package assigned');
    });
  });

  describe('when the history cannot be loaded', () => {
    it('shows the server\'s message', async () => {
      historyResult = () =>
        throwError(() => new ApiFailure(403, 'You are not permitted to perform this action'));
      await createComponent();

      expect(component.errorMessage()).toBe('You are not permitted to perform this action');
      expect(text()).toContain('You are not permitted to perform this action');
    });

    it('announces the failure', async () => {
      historyResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();

      expect(queryAll('[role="alert"]').length).toBeGreaterThan(0);
    });

    it('does not also claim the employee has no package', async () => {
      // A failed request and a genuine coverage gap call for different responses: retry
      // versus assign a package. Showing both would be incoherent.
      historyResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();

      expect(component.hasNoPackage()).toBe(false);
      expect(text()).not.toContain('No compensation package assigned');
    });

    it('falls back to a generic message for a failure it cannot read', async () => {
      historyResult = () => throwError(() => new Error('socket hang up'));
      await createComponent();

      expect(component.errorMessage()).toBe('This compensation history could not be loaded.');
    });

    it('recovers when pointed at an employee that does load', async () => {
      historyResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent(1001);
      expect(component.errorMessage()).not.toBeNull();

      historyResult = () => of([currentRevision()]);
      fixture.componentRef.setInput('employeeId', 1002);
      await settle();

      expect(component.errorMessage()).toBeNull();
      expect(component.current()?.id).toBe(5002);
    });
  });
});
