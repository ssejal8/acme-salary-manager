import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { SalaryComponentDefinition } from '../../components/salary-component.models';
import { SalaryComponentService } from '../../components/salary-component.service';
import { EmployeeSummary } from '../../employees/employee.models';
import { EmployeeService } from '../../employees/employee.service';
import {
  AssignSalaryStructureRequest,
  SalaryStructure,
  StructureTotals,
} from '../salary-structure.models';
import { SalaryStructureService } from '../salary-structure.service';
import { AssignStructure } from './assign-structure';

/** The seeded component definitions, which is what the form builds its rows from. */
const DEFINITIONS: SalaryComponentDefinition[] = [
  def(1, 'BASIC', 'Basic Salary', 'EARNING', 'FLAT', '0.00'),
  def(2, 'HRA', 'House Rent Allowance', 'EARNING', 'FLAT', '0.00'),
  def(5, 'PF', 'Provident Fund', 'DEDUCTION', 'PERCENT_OF_BASIC', '12.00'),
  def(6, 'PROF_TAX', 'Professional Tax', 'DEDUCTION', 'FLAT', '200.00'),
];

function def(
  id: number,
  code: string,
  name: string,
  type: 'EARNING' | 'DEDUCTION',
  calculationType: 'FLAT' | 'PERCENT_OF_BASIC',
  value: string,
): SalaryComponentDefinition {
  return { id, code, name, type, calculationType, value, taxable: true, active: true };
}

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

function totals(overrides: Partial<StructureTotals> = {}): StructureTotals {
  return {
    basicMonthly: '75000.00',
    grossMonthly: '150000.00',
    totalDeductions: '9200.00',
    netMonthly: '140800.00',
    annualCtc: '1800000.00',
    ...overrides,
  };
}

/** A package in force, used to check the form seeds itself for a raise. */
function currentPackage(): SalaryStructure {
  return {
    id: 5002,
    employeeId: 1001,
    effectiveFrom: '2025-04-01',
    current: true,
    createdAt: '2025-03-20T09:30:00Z',
    earnings: [
      {
        componentId: 1,
        code: 'BASIC',
        name: 'Basic Salary',
        type: 'EARNING',
        calculationType: 'FLAT',
        configuredValue: '75000.00',
        monthlyAmount: '75000.00',
      },
    ],
    deductions: [
      {
        componentId: 5,
        code: 'PF',
        name: 'Provident Fund',
        type: 'DEDUCTION',
        calculationType: 'PERCENT_OF_BASIC',
        configuredValue: '12.00',
        monthlyAmount: '9000.00',
      },
    ],
    totals: totals(),
  };
}

/** Past the form's 400ms preview debounce. */
const PAST_DEBOUNCE_MS = 450;

describe('AssignStructure', () => {
  let fixture: ComponentFixture<AssignStructure>;
  let component: AssignStructure;

  let previewRequests: AssignSalaryStructureRequest[];
  let assignRequests: AssignSalaryStructureRequest[];
  let previewResult: () => Observable<StructureTotals>;
  let assignResult: () => Observable<SalaryStructure>;
  let history: SalaryStructure[];
  let employeeResult: () => Observable<EmployeeSummary>;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    previewRequests = [];
    assignRequests = [];
    previewResult = () => of(totals());
    assignResult = () => of(currentPackage());
    history = [];
    employeeResult = () => of(employee());

    TestBed.configureTestingModule({
      providers: [
        // The real router, with only `navigate` spied. Replacing Router wholesale breaks
        // ActivatedRoute, whose factory reads `router.routerState.root` — and the template
        // uses routerLink, which needs a working router anyway.
        provideRouter([]),
        { provide: EmployeeService, useValue: { get: () => employeeResult() } },
        { provide: SalaryComponentService, useValue: { list: () => of(DEFINITIONS) } },
        {
          provide: SalaryStructureService,
          useValue: {
            history: () => of(history),
            preview: (_id: number, request: AssignSalaryStructureRequest) => {
              previewRequests.push(request);
              return previewResult();
            },
            assign: (_id: number, request: AssignSalaryStructureRequest) => {
              assignRequests.push(request);
              return assignResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(id = '1001'): Promise<void> {
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(AssignStructure);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', id);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function waitPastDebounce(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, PAST_DEBOUNCE_MS));
    await settle();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  /** Fills the form the way a user would: a date, and values on the ticked rows. */
  async function fillPackage(effectiveFrom = '2026-04-01'): Promise<void> {
    component.form.controls.effectiveFrom.setValue(effectiveFrom);
    component.rowFor(1).controls['included'].setValue(true);
    component.rowFor(1).controls['value'].setValue('75000.00');
    await waitPastDebounce();
  }

  function lastPreview(): AssignSalaryStructureRequest {
    return previewRequests[previewRequests.length - 1];
  }

  describe('loading', () => {
    it('shows who the package is for', async () => {
      await createComponent();

      expect(text()).toContain('Asha Menon');
      expect(text()).toContain('G3');
      expect(text()).toContain('1 Jun 2022');
    });

    it('builds a row per component definition, grouped by type', async () => {
      await createComponent();

      expect(component.earnings().map((d) => d.code)).toEqual(['BASIC', 'HRA']);
      expect(component.deductions().map((d) => d.code)).toEqual(['PF', 'PROF_TAX']);
      expect(component.componentRows.length).toBe(4);
    });

    it('rejects a route id that is not a valid reference, without requesting anything', async () => {
      await createComponent('abc');

      expect(component.loadError()).toContain('not a valid employee reference');
      expect(previewRequests).toEqual([]);
    });

    it('reports a failure to load', async () => {
      employeeResult = () => throwError(() => new ApiFailure(404, 'Employee 4242 was not found'));
      await createComponent('4242');

      expect(component.loadError()).toBe('Employee 4242 was not found');
    });
  });

  describe('seeding the form', () => {
    it('starts from BASIC alone when there is no package yet', async () => {
      // The form should not suggest that every defined component belongs in every package.
      await createComponent();

      expect(component.isIncluded(1)).toBe(true);
      expect(component.isIncluded(2)).toBe(false);
      expect(component.isIncluded(5)).toBe(false);
    });

    it('offers each component definition\'s default value', async () => {
      await createComponent();

      expect(component.rowFor(5).getRawValue()['value']).toBe('12.00');
      expect(component.rowFor(6).getRawValue()['value']).toBe('200.00');
    });

    it('pre-fills from the package in force, so a raise is a small edit', async () => {
      history = [currentPackage()];
      await createComponent();

      expect(component.isIncluded(1)).toBe(true);
      expect(component.rowFor(1).getRawValue()['value']).toBe('75000.00');
      expect(component.isIncluded(5)).toBe(true);
      expect(component.rowFor(5).getRawValue()['value']).toBe('12.00');
      // Not in the current package, so not ticked.
      expect(component.isIncluded(2)).toBe(false);
      expect(text()).toContain('Pre-filled from the package in force');
    });

    it('leaves the effective date blank even when pre-filling', async () => {
      // The one field that has to be a decision. Defaulting it to today would invite an
      // accidental mid-month revision.
      history = [currentPackage()];
      await createComponent();

      expect(component.form.controls.effectiveFrom.value).toBe('');
    });

    it('does not cost anything before the form is filled in', async () => {
      // Seeding must not look like an edit.
      await createComponent();
      await waitPastDebounce();

      expect(previewRequests).toEqual([]);
    });
  });

  describe('the live preview', () => {
    it('asks the server to cost the package, and shows what comes back', async () => {
      await createComponent();

      await fillPackage();

      expect(previewRequests).toHaveLength(1);
      expect(component.totals()).toEqual(totals());
      expect(text()).toContain('₹150,000.00');
      expect(text()).toContain('₹140,800.00');
      expect(text()).toContain('₹1,800,000.00');
    });

    it('sends only the ticked rows', async () => {
      await createComponent();
      await fillPackage();

      expect(lastPreview().components).toEqual([{ componentId: 1, value: '75000.00' }]);
    });

    it('sends values as strings, never as numbers', async () => {
      // Money must not be routed through a JavaScript number on its way to the server.
      await createComponent();
      await fillPackage();

      for (const line of lastPreview().components) {
        expect(typeof line.value).toBe('string');
      }
    });

    it('includes a row once it is ticked, and drops it once unticked', async () => {
      await createComponent();
      await fillPackage();

      component.rowFor(5).controls['included'].setValue(true);
      await waitPastDebounce();
      expect(lastPreview().components.map((c) => c.componentId)).toEqual([1, 5]);

      component.rowFor(5).controls['included'].setValue(false);
      await waitPastDebounce();
      expect(lastPreview().components.map((c) => c.componentId)).toEqual([1]);
    });

    it('debounces, so a burst of typing is one request', async () => {
      await createComponent();
      component.form.controls.effectiveFrom.setValue('2026-04-01');
      component.rowFor(1).controls['value'].setValue('7');
      component.rowFor(1).controls['value'].setValue('75');
      component.rowFor(1).controls['value'].setValue('750');
      component.rowFor(1).controls['value'].setValue('75000');
      await waitPastDebounce();

      expect(previewRequests).toHaveLength(1);
      expect(lastPreview().components[0].value).toBe('75000');
    });

    it('waits for an effective date before asking', async () => {
      await createComponent();
      component.rowFor(1).controls['value'].setValue('75000.00');
      await waitPastDebounce();

      expect(previewRequests).toEqual([]);
      expect(text()).toContain('Choose an effective date');
    });

    it('computes nothing in the browser', async () => {
      // Every figure on screen is the server's. A total computed here would disagree with
      // the one that gets saved, because components are rounded before being summed.
      previewResult = () => of(totals({ netMonthly: '1.00' }));
      await createComponent();
      await fillPackage();

      // Deliberately inconsistent figures: the screen shows what it was told.
      expect(text()).toContain('₹1.00');
    });
  });

  describe('when the server refuses the package', () => {
    it('shows the reason and clears the stale figures', async () => {
      // A figure beside a rejection is worse than no figure: it reads as costed and fine.
      await createComponent();
      await fillPackage();
      expect(component.totals()).not.toBeNull();

      previewResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Salary structure is not valid', [
              { field: 'components', message: 'a salary structure must include a BASIC component' },
            ]),
        );
      // BASIC out, HRA in — a package with components but no basic. Unticking everything
      // instead would leave nothing to ask the server about, so no rejection to show.
      component.rowFor(1).controls['included'].setValue(false);
      component.rowFor(2).controls['included'].setValue(true);
      component.rowFor(2).controls['value'].setValue('30000.00');
      await waitPastDebounce();

      expect(component.totals()).toBeNull();
      expect(component.componentsMessage()).toContain('must include a BASIC component');
      expect(text()).toContain('must include a BASIC component');
    });

    it('shows no figures and no error when every row is unticked', async () => {
      // Nothing to cost is not a rejection — the form asks for a component instead.
      await createComponent();
      await fillPackage();

      component.rowFor(1).controls['included'].setValue(false);
      await waitPastDebounce();

      expect(component.totals()).toBeNull();
      expect(component.componentsMessage()).toBeNull();
      expect(text()).toContain('at least one component');
    });

    it('surfaces a deductions-exceed-gross rejection', async () => {
      previewResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Salary structure is not valid', [
              {
                field: 'components',
                message: 'deductions (200000.00) exceed gross pay (150000.00)',
              },
            ]),
        );
      await createComponent();
      await fillPackage();

      expect(text()).toContain('exceed gross pay');
    });

    it('surfaces an effective date the server rejects', async () => {
      previewResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Salary structure is not valid', [
              {
                field: 'effectiveFrom',
                message: "must not precede the employee's date of joining (2022-06-01)",
              },
            ]),
        );
      await createComponent();
      await fillPackage('2020-01-01');

      expect(component.effectiveFromMessage()).toContain('must not precede');
      expect(text()).toContain('must not precede');
    });

    it('blocks saving while the package will not cost', async () => {
      previewResult = () => throwError(() => new ApiFailure(400, 'Salary structure is not valid'));
      await createComponent();
      await fillPackage();

      expect(component.canSave()).toBe(false);
      expect(text()).toContain('has to cost successfully before it can be saved');
    });
  });

  /**
   * The grade band asks for itself. A package outside the employee's band is allowed, but
   * only deliberately (FR-4.3), and the server signals that by rejecting the preview with
   * a field error on `overrideReason`.
   */
  describe('a package outside the grade band', () => {
    const bandFailure = () =>
      new ApiFailure(400, 'Salary structure is not valid', [
        {
          field: 'overrideReason',
          message:
            "annual CTC 5000000.00 falls outside grade G3's band (1500000.00–2500000.00); supply an override reason to proceed",
        },
      ]);

    it('reveals the reason box only when the server asks for one', async () => {
      await createComponent();
      await fillPackage();
      expect(component.requiresOverride()).toBe(false);
      expect(text()).not.toContain('Override reason required');

      previewResult = () => throwError(bandFailure);
      component.rowFor(1).controls['value'].setValue('500000.00');
      await waitPastDebounce();

      expect(component.requiresOverride()).toBe(true);
      expect(text()).toContain('Override reason required');
    });

    it("carries the server's own explanation of which band was missed", async () => {
      previewResult = () => throwError(bandFailure);
      await createComponent();
      await fillPackage();

      expect(component.overrideMessage()).toContain("grade G3's band");
      expect(text()).toContain('1500000.00–2500000.00');
    });

    it('sends the reason once given, and costs the package', async () => {
      previewResult = () => throwError(bandFailure);
      await createComponent();
      await fillPackage();

      previewResult = () => of(totals({ annualCtc: '5000000.00' }));
      component.form.controls.overrideReason.setValue('Retention case approved by the CFO');
      await waitPastDebounce();

      expect(lastPreview().overrideReason).toBe('Retention case approved by the CFO');
      expect(component.totals()).not.toBeNull();
    });

    it('omits the reason entirely when it is blank', async () => {
      // An empty string is not "no reason" to a server checking for blankness.
      await createComponent();
      await fillPackage();

      expect('overrideReason' in lastPreview()).toBe(false);
    });
  });

  describe('saving', () => {
    it('will not save a package the server has not costed', async () => {
      await createComponent();

      component.submit();

      expect(assignRequests).toEqual([]);
    });

    it('sends the same request that was previewed', async () => {
      await createComponent();
      await fillPackage();

      component.submit();
      await settle();

      expect(assignRequests).toHaveLength(1);
      expect(assignRequests[0]).toEqual(lastPreview());
    });

    it('returns to the employee record, where the package is now in force', async () => {
      await createComponent();
      await fillPackage();

      component.submit();
      await settle();

      expect(navigate).toHaveBeenCalledWith(['/employees', 1001]);
    });

    it('stays put and explains a rejection', async () => {
      await createComponent();
      await fillPackage();

      assignResult = () =>
        throwError(
          () => new ApiFailure(409, 'A salary structure already takes effect on 2026-04-01'),
        );
      component.submit();
      await settle();

      expect(navigate).not.toHaveBeenCalled();
      expect(component.saving()).toBe(false);
      expect(text()).toContain('already takes effect');
    });

    it('ignores a second submit while one is in flight', async () => {
      await createComponent();
      await fillPackage();
      assignResult = () => new Observable<SalaryStructure>(() => undefined);

      component.submit();
      component.submit();

      expect(assignRequests).toHaveLength(1);
    });
  });
});
