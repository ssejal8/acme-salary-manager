import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { SalaryStructureService } from '../../structures/salary-structure.service';
import { EmployeeSummary } from '../employee.models';
import { EmployeeService } from '../employee.service';
import { EmployeeDetail } from './employee-detail';

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

describe('EmployeeDetail', () => {
  let fixture: ComponentFixture<EmployeeDetail>;
  let component: EmployeeDetail;

  let requestedIds: number[];
  /** Employee ids the embedded salary history asked for. */
  let structureRequests: number[];
  let getResult: () => Observable<EmployeeSummary>;
  let deactivateCalls: { id: number; exitDate: string }[];
  let deactivateResult: () => Observable<EmployeeSummary>;

  beforeEach(() => {
    requestedIds = [];
    structureRequests = [];
    deactivateCalls = [];
    getResult = () => of(employee());
    deactivateResult = () =>
      of(employee({ status: 'INACTIVE', exitDate: '2026-08-31' }));

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: EmployeeService,
          useValue: {
            get: (id: number) => {
              requestedIds.push(id);
              return getResult();
            },
            deactivate: (id: number, request: { exitDate: string }) => {
              deactivateCalls.push({ id, exitDate: request.exitDate });
              return deactivateResult();
            },
          },
        },
        // This screen renders <app-salary-history>, which fetches on its own. Stubbed
        // rather than left to the real service so these tests stay free of HTTP — and so
        // the id handed down is observable, which is the whole of the integration.
        {
          provide: SalaryStructureService,
          useValue: {
            history: (employeeId: number) => {
              structureRequests.push(employeeId);
              return of([]);
            },
          },
        },
      ],
    });
  });

  async function createComponent(id = '1001'): Promise<void> {
    fixture = TestBed.createComponent(EmployeeDetail);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', id);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('requests the employee named in the route', async () => {
    await createComponent('1001');

    expect(requestedIds).toEqual([1001]);
  });

  it('shows the record', async () => {
    await createComponent();

    expect(text()).toContain('Asha Menon');
    expect(text()).toContain('E-1001');
    expect(text()).toContain('asha.menon@acme.test');
    expect(text()).toContain('Engineering');
    expect(text()).toContain('Senior Software Engineer');
    expect(text()).toContain('G3');
  });

  it('shows the joining date without shifting it by a timezone', async () => {
    await createComponent();

    expect(text()).toContain('1 Jun 2022');
  });

  it('shows a dash for an absent exit date, because absent means still employed', async () => {
    await createComponent();

    expect(text()).toContain('—');
  });

  it('marks a leaver with their exit date', async () => {
    getResult = () => of(employee({ status: 'INACTIVE', exitDate: '2026-03-31' }));

    await createComponent();

    expect(text()).toContain('Left 31 Mar 2026');
  });

  describe('the compensation section', () => {
    it('is rendered for the loaded employee', async () => {
      await createComponent();

      expect(text()).toContain('Compensation');
    });

    it('is given the employee id, not the raw route segment', async () => {
      // The child takes a number, so the parent is what turns "1001" into 1001 — and it
      // only does so once a real employee has loaded.
      await createComponent('1001');

      expect(structureRequests).toEqual([1001]);
    });

    it('is not asked for at all when the employee could not be loaded', async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Employee 4242 was not found'));

      await createComponent('4242');

      expect(structureRequests).toEqual([]);
    });

    it('is not asked for when the route id is not a valid reference', async () => {
      await createComponent('abc');

      expect(structureRequests).toEqual([]);
    });
  });

  it("links to this employee's payslips", async () => {
    // FR-6.5 filters payslips by employee, and this is how that filter is reached —
    // rather than giving the register an employee picker of its own.
    await createComponent('1001');

    const link = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('a'),
    ).find((anchor) => anchor.textContent?.trim() === 'Payslips');
    expect(link?.getAttribute('href')).toBe('/payslips/all?employeeId=1001');
  });

  it('refetches when the route moves to another employee', async () => {
    await createComponent('1001');

    fixture.componentRef.setInput('id', '1002');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(requestedIds).toEqual([1001, 1002]);
  });

  describe('a reference that is not a valid id', () => {
    it('rejects it without issuing a request', async () => {
      await createComponent('abc');

      expect(requestedIds).toEqual([]);
      expect(component.errorMessage()).toContain('not a valid employee reference');
    });

    it('rejects a zero or negative id', async () => {
      await createComponent('0');
      expect(requestedIds).toEqual([]);

      await createComponent('-3');
      expect(requestedIds).toEqual([]);
    });

    it('rejects a non-integer id', async () => {
      await createComponent('1.5');

      expect(requestedIds).toEqual([]);
    });
  });

  describe('when the record cannot be loaded', () => {
    it("shows the server's message", async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Employee 4242 was not found'));

      await createComponent('4242');

      expect(component.errorMessage()).toBe('Employee 4242 was not found');
      expect(text()).toContain('Employee 4242 was not found');
    });

    it('announces the failure', async () => {
      getResult = () => throwError(() => new ApiFailure(403, 'Not permitted'));

      await createComponent();

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
      ).toContain('Not permitted');
    });

    it('does not render a half-empty record alongside the error', async () => {
      getResult = () => throwError(() => new ApiFailure(404, 'Not found'));

      await createComponent();

      expect(component.employee()).toBeNull();
      expect(text()).not.toContain('Work email');
    });

    it('falls back to a generic message for a failure it cannot read', async () => {
      getResult = () => throwError(() => new Error('socket hang up'));

      await createComponent();

      expect(component.errorMessage()).toBe('This employee could not be loaded.');
    });
  });

  it('offers a way back to the list', async () => {
    await createComponent();

    const breadcrumb = (fixture.nativeElement as HTMLElement).querySelector('.breadcrumb__link');
    expect(breadcrumb?.getAttribute('href')).toBe('/employees');
  });

  it('links to the edit screen for this employee', async () => {
    await createComponent('1001');

    const edit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('a'),
    ).find((link) => link.textContent?.trim() === 'Edit');
    expect(edit?.getAttribute('href')).toBe('/employees/1001/edit');
  });

  /** Recording an exit (FR-2.5). */
  describe('recording an exit', () => {
    async function openExitForm(): Promise<void> {
      component.toggleExitForm();
      await settle();
    }

    async function settle(): Promise<void> {
      await fixture.whenStable();
      fixture.detectChanges();
    }

    it('is offered for an active employee and hidden for a leaver', async () => {
      await createComponent();
      expect(text()).toContain('Record exit');

      getResult = () => of(employee({ status: 'INACTIVE', exitDate: '2026-03-31' }));
      await createComponent();

      expect(text()).not.toContain('Record exit');
    });

    it('does not submit without a date', async () => {
      // Never defaulted to today: payroll eligibility is derived from it (FR-2.6).
      await createComponent();
      await openExitForm();

      component.deactivate();

      expect(deactivateCalls).toEqual([]);
      expect(component.exitDateError()).toBe('This is required');
    });

    it('sends the date for this employee', async () => {
      await createComponent('1001');
      await openExitForm();
      component.exitForm.setValue({ exitDate: '2026-08-31' });

      component.deactivate();
      await settle();

      expect(deactivateCalls).toEqual([{ id: 1001, exitDate: '2026-08-31' }]);
    });

    it('shows the exit on the record without refetching it', async () => {
      // The response carries the stored record, so a second GET would be a round trip for
      // something already in hand.
      await createComponent('1001');
      await openExitForm();
      component.exitForm.setValue({ exitDate: '2026-08-31' });

      component.deactivate();
      await settle();

      expect(text()).toContain('Left 31 Aug 2026');
      expect(requestedIds).toEqual([1001]);
      expect(text()).not.toContain('Record exit');
    });

    it("shows the server's refusal for someone who has already left", async () => {
      deactivateResult = () =>
        throwError(() => new ApiFailure(409, 'Employee E-1001 already left on 2026-03-31'));
      await createComponent();
      await openExitForm();
      component.exitForm.setValue({ exitDate: '2026-08-31' });

      component.deactivate();
      await settle();

      expect(component.deactivateError()).toContain('already left');
      expect(text()).toContain('already left on 2026-03-31');
      // Still active on screen, because the exit was refused.
      expect(text()).toContain('Active');
    });

    it("shows the server's field error against the date", async () => {
      deactivateResult = () =>
        throwError(
          () =>
            new ApiFailure(400, 'Validation failed', [
              { field: 'exitDate', message: 'must not precede the date of joining' },
            ]),
        );
      await createComponent();
      await openExitForm();
      component.exitForm.setValue({ exitDate: '2020-01-01' });

      component.deactivate();
      await settle();

      expect(component.exitDateError()).toContain('must not precede the date of joining');
    });

    it('ignores a second submit while one is in flight', async () => {
      deactivateResult = () => new Observable<EmployeeSummary>(() => undefined);
      await createComponent();
      await openExitForm();
      component.exitForm.setValue({ exitDate: '2026-08-31' });

      component.deactivate();
      component.deactivate();

      expect(deactivateCalls).toHaveLength(1);
    });

    it('forgets the outcome when the route moves to another employee', async () => {
      await createComponent('1001');
      await openExitForm();
      component.exitForm.setValue({ exitDate: '2026-08-31' });
      component.deactivate();
      await settle();
      expect(text()).toContain('Left 31 Aug 2026');

      fixture.componentRef.setInput('id', '1002');
      await settle();

      // Otherwise the next employee would appear to have left on this one's date.
      expect(text()).not.toContain('Left 31 Aug 2026');
      expect(text()).toContain('Active');
    });
  });
});
