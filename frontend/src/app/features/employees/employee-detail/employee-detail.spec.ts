import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
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
  let getResult: () => Observable<EmployeeSummary>;

  beforeEach(() => {
    requestedIds = [];
    getResult = () => of(employee());

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

  it('carries no compensation, and says so rather than leaving a gap', async () => {
    await createComponent();

    expect(text()).toContain('not part of this screen yet');
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
});
