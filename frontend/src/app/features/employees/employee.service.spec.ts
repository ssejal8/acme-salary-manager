import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DEFAULT_EMPLOYEE_QUERY, EmployeeQuery } from './employee.models';
import { EmployeeService, toParams } from './employee.service';

const EMPLOYEES_URL = '/api/v1/employees';

describe('EmployeeService', () => {
  let employees: EmployeeService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    employees = TestBed.inject(EmployeeService);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('requests the list from the employees endpoint', () => {
    employees.list(DEFAULT_EMPLOYEE_QUERY).subscribe();

    const request = backend.expectOne((candidate) => candidate.url === EMPLOYEES_URL);
    expect(request.request.method).toBe('GET');
    request.flush({ content: [] });
  });

  it('requests one employee by id', () => {
    employees.get(1001).subscribe();

    const request = backend.expectOne(`${EMPLOYEES_URL}/1001`);
    expect(request.request.method).toBe('GET');
    request.flush({});
  });

  /**
   * The query string is worth testing directly. Each of these rules exists because the
   * server rejects the alternative, so getting one wrong is a 400 rather than a
   * mis-filtered list.
   */
  describe('building the query string', () => {
    function params(query: Partial<EmployeeQuery> = {}): URLSearchParams {
      return new URLSearchParams(toParams({ ...DEFAULT_EMPLOYEE_QUERY, ...query }).toString());
    }

    it('sends the server defaults explicitly', () => {
      const sent = params();

      expect(sent.get('status')).toBe('ACTIVE_ONLY');
      expect(sent.get('page')).toBe('0');
      expect(sent.get('size')).toBe('20');
    });

    it("uses Spring's property,direction form for sorting", () => {
      expect(params().get('sort')).toBe('employeeCode,asc');
      expect(params({ sort: 'department', direction: 'desc' }).get('sort')).toBe(
        'department,desc',
      );
    });

    it('omits filters that are not set', () => {
      // `?departmentId=` is a bind failure on a Long, not an absent filter.
      const sent = params();

      expect(sent.has('q')).toBe(false);
      expect(sent.has('departmentId')).toBe(false);
      expect(sent.has('designationId')).toBe(false);
      expect(sent.has('gradeId')).toBe(false);
    });

    it('sends the reference filters that are set', () => {
      const sent = params({ departmentId: 1, designationId: 20, gradeId: 30 });

      expect(sent.get('departmentId')).toBe('1');
      expect(sent.get('designationId')).toBe('20');
      expect(sent.get('gradeId')).toBe('30');
    });

    it('trims the name search and omits it when it is only whitespace', () => {
      expect(params({ q: '  asha  ' }).get('q')).toBe('asha');
      expect(params({ q: '   ' }).has('q')).toBe(false);
      expect(params({ q: '' }).has('q')).toBe(false);
    });

    it('sends a status filter that asks for leavers', () => {
      expect(params({ status: 'ALL' }).get('status')).toBe('ALL');
      expect(params({ status: 'INACTIVE_ONLY' }).get('status')).toBe('INACTIVE_ONLY');
    });

    it('sends the requested page', () => {
      expect(params({ page: 3 }).get('page')).toBe('3');
    });
  });
});
