import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { ApiFailure } from '../../../core/http/api-error';
import { Role } from '../../../core/auth/auth.models';
import { CURRENCY_SYMBOL } from '../../../shared/money';
import {
  CreateSalaryComponentRequest,
  SalaryComponentDefinition,
} from '../salary-component.models';
import { SalaryComponentService } from '../salary-component.service';
import { ComponentList } from './component-list';

function def(overrides: Partial<SalaryComponentDefinition> = {}): SalaryComponentDefinition {
  return {
    id: 1,
    code: 'BASIC',
    name: 'Basic Salary',
    type: 'EARNING',
    calculationType: 'FLAT',
    value: '0.00',
    taxable: true,
    active: true,
    ...overrides,
  };
}

const SEEDED: SalaryComponentDefinition[] = [
  def(),
  def({ id: 2, code: 'HRA', name: 'House Rent Allowance' }),
  def({
    id: 5,
    code: 'PF',
    name: 'Provident Fund',
    type: 'DEDUCTION',
    calculationType: 'PERCENT_OF_BASIC',
    value: '12.00',
    taxable: false,
  }),
  def({
    id: 6,
    code: 'PROF_TAX',
    name: 'Professional Tax',
    type: 'DEDUCTION',
    value: '200.00',
    taxable: false,
  }),
];

describe('ComponentList', () => {
  let fixture: ComponentFixture<ComponentList>;
  let component: ComponentList;

  let role: Role;
  let listCalls: boolean[];
  let createRequests: CreateSalaryComponentRequest[];
  let listResult: () => Observable<SalaryComponentDefinition[]>;
  let createResult: () => Observable<SalaryComponentDefinition>;

  beforeEach(() => {
    role = 'ADMIN';
    listCalls = [];
    createRequests = [];
    listResult = () => of(SEEDED);
    createResult = () => of(def({ id: 9, code: 'BONUS', name: 'Annual Bonus' }));

    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: { hasAnyRole: (...roles: Role[]) => roles.includes(role) },
        },
        {
          provide: SalaryComponentService,
          useValue: {
            list: (includeInactive: boolean) => {
              listCalls.push(includeInactive);
              return listResult();
            },
            create: (request: CreateSalaryComponentRequest) => {
              createRequests.push(request);
              return createResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(ComponentList);
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

  function query(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  describe('listing', () => {
    it('excludes retired components by default', async () => {
      // The common caller is a form building a new package, and a retired component
      // cannot go into one.
      await createComponent();

      expect(listCalls).toEqual([false]);
    });

    it('groups definitions by type', async () => {
      await createComponent();

      expect(component.earnings().map((d) => d.code)).toEqual(['BASIC', 'HRA']);
      expect(component.deductions().map((d) => d.code)).toEqual(['PF', 'PROF_TAX']);
      expect(text()).toContain('Earnings');
      expect(text()).toContain('Deductions');
    });

    it('shows a flat default as money and a percentage as a rate', async () => {
      // The bug this guards: 12.00 through the money formatter reads as ₹12.00 for what
      // is a twelve-per-cent deduction.
      await createComponent();

      expect(text()).toContain('₹200.00');
      expect(text()).toContain('12%');
      expect(text()).not.toContain(`${CURRENCY_SYMBOL}12.00`);
    });

    it('asks for retired components only when told to', async () => {
      await createComponent();

      component.toggleInactive(true);
      await settle();

      expect(listCalls).toEqual([false, true]);
    });

    it('marks a retired component rather than hiding it', async () => {
      // A historical payslip references it, so it must not look deleted.
      listResult = () => of([def({ id: 7, code: 'OLD_ALLOWANCE', active: false })]);
      await createComponent();

      expect(text()).toContain('Retired');
      expect(query('.components__row--retired')).not.toBeNull();
    });

    it('says so when nothing is defined', async () => {
      listResult = () => of([]);
      await createComponent();

      expect(text()).toContain('No components are defined');
    });

    it("shows the server's message on failure", async () => {
      listResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));
      await createComponent();

      expect(component.errorMessage()).toBe('Not permitted');
      expect(text()).toContain('Not permitted');
    });
  });

  /**
   * Only ADMIN may define a component. Hiding the form from HR is a courtesy — the
   * endpoint is `hasRole('ADMIN')` and would refuse them regardless — but it saves them a
   * form that could only answer 403.
   */
  describe('who may define one', () => {
    it('offers the form to ADMIN', async () => {
      role = 'ADMIN';
      await createComponent();

      expect(component.canCreate()).toBe(true);
      expect(text()).toContain('Define a component');
    });

    it('does not offer it to HR, but still shows the list', async () => {
      role = 'HR';
      await createComponent();

      expect(component.canCreate()).toBe(false);
      expect(text()).not.toContain('Define a component');
      expect(text()).toContain('Basic Salary');
    });
  });

  describe('defining a component', () => {
    beforeEach(async () => {
      role = 'ADMIN';
      await createComponent();
      component.toggleForm();
      await settle();
    });

    it('does not submit an empty form', async () => {
      component.submit();

      expect(createRequests).toEqual([]);
    });

    it('sends what was entered', async () => {
      component.form.setValue({
        code: 'BONUS',
        name: 'Annual Bonus',
        type: 'EARNING',
        calculationType: 'FLAT',
        value: '5000.00',
        taxable: true,
      });

      component.submit();
      await settle();

      expect(createRequests).toEqual([
        {
          code: 'BONUS',
          name: 'Annual Bonus',
          type: 'EARNING',
          calculationType: 'FLAT',
          value: '5000.00',
          taxable: true,
        },
      ]);
    });

    it('sends the value as a string, never a number', async () => {
      component.form.patchValue({ code: 'BONUS', name: 'Annual Bonus', value: '5000.00' });

      component.submit();
      await settle();

      expect(typeof createRequests[0].value).toBe('string');
    });

    it('refetches afterwards rather than appending the row locally', async () => {
      // The server uppercases the code and fills in defaults, so the row it returns is
      // the truth. Appending our own would show a row that differs from the stored one.
      component.form.patchValue({ code: 'bonus', name: 'Annual Bonus' });
      const before = listCalls.length;

      component.submit();
      await settle();

      expect(listCalls.length).toBe(before + 1);
    });

    it('confirms with the code the server stored', async () => {
      component.form.patchValue({ code: 'bonus', name: 'Annual Bonus' });

      component.submit();
      await settle();

      expect(component.createdCode()).toBe('BONUS');
      expect(text()).toContain('BONUS');
    });

    it('clears the form so a second component can be added', async () => {
      component.form.patchValue({ code: 'BONUS', name: 'Annual Bonus', value: '5000.00' });

      component.submit();
      await settle();

      expect(component.form.controls.code.value).toBe('');
      expect(component.form.controls.value.value).toBe('0.00');
    });

    it("shows the server's field error, which knows about code uniqueness", async () => {
      createResult = () =>
        throwError(
          () =>
            new ApiFailure(409, 'The request conflicts with existing data', [
              { field: 'code', message: 'a component with code BASIC already exists' },
            ]),
        );
      component.form.patchValue({ code: 'BASIC', name: 'Basic Salary' });

      component.submit();
      await settle();

      expect(component.fieldError('code')).toContain('already exists');
      expect(text()).toContain('already exists');
    });

    it('surfaces the 100 per cent ceiling the server enforces', async () => {
      createResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Validation failed', [
              { field: 'value', message: 'must not exceed 100 percent' },
            ]),
        );
      component.form.patchValue({
        code: 'HUGE',
        name: 'Huge deduction',
        calculationType: 'PERCENT_OF_BASIC',
        value: '150',
      });

      component.submit();
      await settle();

      expect(component.fieldError('value')).toContain('100 percent');
    });

    it('shows an envelope message that names no field', async () => {
      createResult = () => throwError(() => new ApiFailure(409, 'The request conflicts with existing data'));
      component.form.patchValue({ code: 'BONUS', name: 'Annual Bonus' });

      component.submit();
      await settle();

      expect(text()).toContain('conflicts with existing data');
    });

    it('labels the value by calculation type, so the number is never ambiguous', async () => {
      expect(component.valueUnit()).toBe('per month');

      component.form.controls.calculationType.setValue('PERCENT_OF_BASIC');
      await settle();

      expect(component.valueUnit()).toBe('% of basic');
    });

    it('ignores a second submit while one is in flight', async () => {
      createResult = () => new Observable<SalaryComponentDefinition>(() => undefined);
      component.form.patchValue({ code: 'BONUS', name: 'Annual Bonus' });

      component.submit();
      component.submit();

      expect(createRequests).toHaveLength(1);
    });
  });
});
