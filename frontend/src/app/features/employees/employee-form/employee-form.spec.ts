import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { ReferenceData } from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import {
  CreateEmployeeRequest,
  EmployeeSummary,
  UpdateEmployeeRequest,
} from '../employee.models';
import { EmployeeService } from '../employee.service';
import { EmployeeForm } from './employee-form';

const REFERENCE_DATA: ReferenceData = {
  departments: [
    { id: 1, code: 'ENG', name: 'Engineering' },
    { id: 2, code: 'FIN', name: 'Finance' },
  ],
  designations: [
    { id: 20, title: 'Software Engineer' },
    { id: 21, title: 'Finance Analyst' },
  ],
  grades: [
    { id: 30, name: 'G2', minCtc: '800000.00', maxCtc: '1500000.00' },
    { id: 31, name: 'G3', minCtc: '1500000.00', maxCtc: '2500000.00' },
  ],
};

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
    designation: { id: 20, label: 'Software Engineer' },
    grade: { id: 30, label: 'G2' },
    ...overrides,
  };
}

const COMPLETE_FORM = {
  employeeCode: 'E-2001',
  firstName: 'Ravi',
  lastName: 'Iyer',
  workEmail: 'ravi.iyer@acme.test',
  dateOfJoining: '2026-04-01',
  departmentId: 1,
  designationId: 20,
  gradeId: 30,
};

describe('EmployeeForm', () => {
  let fixture: ComponentFixture<EmployeeForm>;
  let component: EmployeeForm;
  let router: Router;

  let createRequests: CreateEmployeeRequest[];
  let updateRequests: { id: number; request: UpdateEmployeeRequest }[];
  let navigations: unknown[][];

  let createResult: () => Observable<EmployeeSummary>;
  let updateResult: () => Observable<EmployeeSummary>;
  let getResult: () => Observable<EmployeeSummary>;
  let referenceResult: () => Observable<ReferenceData>;

  beforeEach(() => {
    createRequests = [];
    updateRequests = [];
    navigations = [];
    createResult = () => of(employee({ id: 7, employeeCode: 'E-2001' }));
    updateResult = () => of(employee({ lastName: 'Iyer' }));
    getResult = () => of(employee());
    referenceResult = () => of(REFERENCE_DATA);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: EmployeeService,
          useValue: {
            get: () => getResult(),
            create: (request: CreateEmployeeRequest) => {
              createRequests.push(request);
              return createResult();
            },
            update: (id: number, request: UpdateEmployeeRequest) => {
              updateRequests.push({ id, request });
              return updateResult();
            },
          },
        },
        {
          provide: ReferenceDataService,
          useValue: { all: () => referenceResult() },
        },
      ],
    });

    router = TestBed.inject(Router);
    // Recorded rather than performed: this component's job ends at asking to navigate.
    router.navigate = (commands: unknown[]) => {
      navigations.push(commands);
      return Promise.resolve(true);
    };
  });

  /** @param id omitted for the create screen, supplied for the edit screen. */
  async function createComponent(id?: string): Promise<void> {
    fixture = TestBed.createComponent(EmployeeForm);
    component = fixture.componentInstance;
    if (id !== undefined) {
      fixture.componentRef.setInput('id', id);
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

  function query<T extends HTMLElement>(selector: string): T | null {
    return (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  }

  describe('creating', () => {
    it('offers an empty form with the reference data loaded', async () => {
      await createComponent();

      expect(component.editing()).toBe(false);
      expect(text()).toContain('Add an employee');
      expect(text()).toContain('Engineering');
      expect(text()).toContain('Software Engineer');
      expect(text()).toContain('G2');
    });

    it('defaults nothing — every field is a fact somebody has to supply', async () => {
      await createComponent();

      expect(component.form.getRawValue()).toEqual({
        employeeCode: '',
        firstName: '',
        lastName: '',
        workEmail: '',
        dateOfJoining: '',
        departmentId: null,
        designationId: null,
        gradeId: null,
      });
    });

    it('does not submit an incomplete form', async () => {
      await createComponent();

      component.submit();

      expect(createRequests).toEqual([]);
    });

    it('sends what was entered', async () => {
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      await settle();

      expect(createRequests).toEqual([COMPLETE_FORM]);
    });

    it('sends the reference ids as numbers, not the strings a select would bind', async () => {
      await createComponent();
      const department = query<HTMLSelectElement>('#departmentId');
      component.form.patchValue({ ...COMPLETE_FORM });
      department!.selectedIndex = 2; // past the disabled placeholder, onto Finance
      department!.dispatchEvent(new Event('change'));
      await settle();

      component.submit();
      await settle();

      expect(typeof createRequests[0].departmentId).toBe('number');
      expect(createRequests[0].departmentId).toBe(2);
    });

    it('trims a padded value when the field loses focus', async () => {
      // A pasted address arrives as " ravi.iyer@acme.test ", which the email validator
      // rejects — so without trimming on blur the form refuses a value it was about to
      // trim and send anyway, and tells the user their address is not an address.
      await createComponent();
      component.form.setValue({
        ...COMPLETE_FORM,
        firstName: '  Ravi  ',
        workEmail: ' ravi.iyer@acme.test ',
      });
      expect(component.form.controls.workEmail.valid).toBe(false);

      query<HTMLInputElement>('#firstName')!.dispatchEvent(new Event('blur'));
      query<HTMLInputElement>('#workEmail')!.dispatchEvent(new Event('blur'));
      await settle();

      expect(component.form.controls.workEmail.valid).toBe(true);

      component.submit();
      await settle();

      expect(createRequests[0].firstName).toBe('Ravi');
      expect(createRequests[0].workEmail).toBe('ravi.iyer@acme.test');
    });

    it('goes to the record the server created', async () => {
      // Not to the list: the next thing HR does is assign a package.
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      await settle();

      expect(navigations).toEqual([['/employees', 7]]);
    });

    it('shows a duplicate code against the field the server named', async () => {
      // FR-2.2: uniqueness cannot be checked here, so the 409 carries the field.
      createResult = () =>
        throwError(
          () =>
            new ApiFailure(409, 'An employee with these details already exists', [
              { field: 'employeeCode', message: 'employee code E-2001 is already in use' },
            ]),
        );
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      await settle();

      expect(component.fieldError('employeeCode')).toContain('already in use');
      expect(text()).toContain('already in use');
    });

    it('shows both duplicates when both are taken', async () => {
      createResult = () =>
        throwError(
          () =>
            new ApiFailure(409, 'An employee with these details already exists', [
              { field: 'employeeCode', message: 'employee code E-2001 is already in use' },
              {
                field: 'workEmail',
                message: 'ravi.iyer@acme.test already belongs to another employee',
              },
            ]),
        );
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      await settle();

      expect(component.fieldError('employeeCode')).toContain('already in use');
      expect(component.fieldError('workEmail')).toContain('already belongs');
    });

    it('shows an envelope failure that names no field above the form', async () => {
      createResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      await settle();

      expect(component.generalError()).toBe('Not permitted');
      expect(query('[role="alert"]')?.textContent).toContain('Not permitted');
    });

    it('ignores a second submit while one is in flight', async () => {
      createResult = () => new Observable<EmployeeSummary>(() => undefined);
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      component.submit();

      expect(createRequests).toHaveLength(1);
    });
  });

  describe('editing', () => {
    it('loads the record and seeds the form from it', async () => {
      await createComponent('1001');

      expect(component.editing()).toBe(true);
      expect(text()).toContain('Edit employee');
      expect(component.form.getRawValue()).toMatchObject({
        firstName: 'Asha',
        lastName: 'Menon',
        workEmail: 'asha.menon@acme.test',
        departmentId: 1,
        designationId: 20,
        gradeId: 30,
      });
    });

    it('will not let the code or joining date be edited', async () => {
      // FR-2.3: the code is on published payslips and the joining date is what every
      // salary revision is validated against.
      await createComponent('1001');

      expect(component.form.controls.employeeCode.disabled).toBe(true);
      expect(component.form.controls.dateOfJoining.disabled).toBe(true);
      expect(query('#employeeCode')).toBeNull();
      expect(query('#dateOfJoining')).toBeNull();
    });

    it('says why they cannot change rather than showing a greyed-out box', async () => {
      await createComponent('1001');

      expect(text()).toContain('appears on published payslips');
      expect(text()).toContain('E-1001');
      expect(text()).toContain('1 Jun 2022');
    });

    it('sends only the editable fields', async () => {
      await createComponent('1001');
      component.form.patchValue({ lastName: 'Menon-Rao' });

      component.submit();
      await settle();

      expect(updateRequests).toEqual([
        {
          id: 1001,
          request: {
            firstName: 'Asha',
            lastName: 'Menon-Rao',
            workEmail: 'asha.menon@acme.test',
            departmentId: 1,
            designationId: 20,
            gradeId: 30,
          },
        },
      ]);
    });

    it('returns to the record afterwards', async () => {
      await createComponent('1001');
      component.form.patchValue({ lastName: 'Menon-Rao' });

      component.submit();
      await settle();

      expect(navigations).toEqual([['/employees', 1001]]);
    });

    it('offers Cancel back to the record, not the list', async () => {
      await createComponent('1001');

      expect(component.cancelTarget()).toEqual(['/employees', 1001]);
    });

    it('rejects a route id that is not a valid reference, without requesting it', async () => {
      await createComponent('abc');

      expect(component.loadError()).toContain('not a valid employee reference');
      expect(component.editing()).toBe(false);
    });

    it("shows the server's message when the record cannot be loaded", async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Employee 4242 was not found'));

      await createComponent('4242');

      expect(component.loadError()).toBe('Employee 4242 was not found');
      expect(text()).toContain('Employee 4242 was not found');
    });
  });

  describe('client-side validation', () => {
    it('mirrors the API rather than owning the rules', async () => {
      await createComponent();
      component.form.patchValue({ workEmail: 'not-an-email' });
      component.form.controls.workEmail.markAsTouched();
      await settle();

      expect(component.fieldError('workEmail')).toContain('email address');
    });

    it('prefers the server message where there is one', async () => {
      // The server knows things this form cannot — that a grade id no longer exists.
      createResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Validation failed', [
              { field: 'gradeId', message: 'does not exist' },
            ]),
        );
      await createComponent();
      component.form.setValue(COMPLETE_FORM);

      component.submit();
      await settle();

      expect(component.fieldError('gradeId')).toBe('does not exist');
    });
  });
});
