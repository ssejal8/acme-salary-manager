import { DEFAULT_EMPLOYEE_QUERY, EmployeeQuery } from './employee.models';
import {
  employeeQueriesEqual,
  parseEmployeeQuery,
  toEmployeeQueryParams,
} from './employee-query';

/**
 * `URLSearchParams` satisfies the codec's `ReadonlyParams` interface structurally, so a
 * test can hand it a literal query string — the same thing a user pastes into the address
 * bar.
 */
function parse(queryString: string): EmployeeQuery {
  return parseEmployeeQuery(new URLSearchParams(queryString));
}

function query(overrides: Partial<EmployeeQuery> = {}): EmployeeQuery {
  return { ...DEFAULT_EMPLOYEE_QUERY, ...overrides };
}

describe('employee list URL codec', () => {
  describe('reading a URL', () => {
    it('gives the defaults for an empty query string', () => {
      expect(parse('')).toEqual(DEFAULT_EMPLOYEE_QUERY);
    });

    it('reads every parameter', () => {
      const parsed = parse(
        'q=asha&departmentId=1&designationId=20&gradeId=30&status=ALL&page=3&size=50&sort=lastName&direction=desc',
      );

      expect(parsed).toEqual({
        q: 'asha',
        departmentId: 1,
        designationId: 20,
        gradeId: 30,
        status: 'ALL',
        page: 2,
        size: 50,
        sort: 'lastName',
        direction: 'desc',
      });
    });

    it('treats the page in the URL as 1-based', () => {
      // A link that reads page=2 and shows the third page would be a permanent small
      // confusion for whoever shared it.
      expect(parse('page=1').page).toBe(0);
      expect(parse('page=2').page).toBe(1);
      expect(parse('page=12').page).toBe(11);
    });

    it('trims the search term and ignores a blank one', () => {
      expect(parse('q=%20%20asha%20%20').q).toBe('asha');
      expect(parse('q=').q).toBeUndefined();
      expect(parse('q=%20%20').q).toBeUndefined();
    });
  });

  /**
   * A query string is untrusted input: hand-edited, bookmarked from an older version of
   * the screen, or mangled in transit. None of it may reach the server as a bad request or
   * break the screen.
   */
  describe('reading a URL that cannot be trusted', () => {
    it('drops a sort key the API would reject', () => {
      // The important one. The API answers 400 for a key outside its whitelist rather
      // than ignoring it, so forwarding this would turn a stale bookmark into an error
      // page. It is also the shape of an attempt to sort by something private.
      expect(parse('sort=passwordHash').sort).toBe('employeeCode');
      expect(parse('sort=department.name').sort).toBe('employeeCode');
      expect(parse('sort=').sort).toBe('employeeCode');
    });

    it('accepts every key the API does', () => {
      for (const key of [
        'employeeCode',
        'firstName',
        'lastName',
        'workEmail',
        'dateOfJoining',
        'exitDate',
        'status',
        'department',
        'designation',
        'grade',
      ]) {
        expect(parse(`sort=${key}`).sort).toBe(key);
      }
    });

    it('drops a reference id that is not a positive integer', () => {
      // `?departmentId=abc` would otherwise reach the server as a value that fails to
      // bind to a Long — a 400 instead of an unfiltered list.
      expect(parse('departmentId=abc').departmentId).toBeUndefined();
      expect(parse('departmentId=0').departmentId).toBeUndefined();
      expect(parse('departmentId=-1').departmentId).toBeUndefined();
      expect(parse('departmentId=1.5').departmentId).toBeUndefined();
      expect(parse('departmentId=').departmentId).toBeUndefined();
      expect(parse('gradeId=NaN').gradeId).toBeUndefined();
    });

    it('falls back to the first page for an unusable page number', () => {
      expect(parse('page=abc').page).toBe(0);
      expect(parse('page=0').page).toBe(0);
      expect(parse('page=-5').page).toBe(0);
      expect(parse('page=1.5').page).toBe(0);
    });

    it('falls back to the default size rather than clamping an absurd one', () => {
      // Clamping 5000 to 100 would show "100" as the chosen value, implying the user
      // picked it — and the server clamps to 100 silently anyway, so the control would
      // be asserting something about the response it cannot know.
      expect(parse('size=5000').size).toBe(20);
      expect(parse('size=7').size).toBe(20);
      expect(parse('size=0').size).toBe(20);
      expect(parse('size=abc').size).toBe(20);
    });

    it('accepts each offered size', () => {
      expect(parse('size=10').size).toBe(10);
      expect(parse('size=20').size).toBe(20);
      expect(parse('size=50').size).toBe(50);
      expect(parse('size=100').size).toBe(100);
    });

    it('falls back for an unknown status or direction', () => {
      expect(parse('status=RETIRED').status).toBe('ACTIVE_ONLY');
      expect(parse('status=active_only').status).toBe('ACTIVE_ONLY');
      expect(parse('direction=sideways').direction).toBe('asc');
      expect(parse('direction=DESC').direction).toBe('asc');
    });

    it('ignores parameters that are not part of the contract', () => {
      expect(parse('utm_source=slack&q=asha')).toEqual(query({ q: 'asha' }));
    });
  });

  describe('writing a URL', () => {
    it('writes nothing at all for the default query', () => {
      // So a freshly opened list is /employees, not /employees?status=ACTIVE_ONLY&page=0…
      expect(toEmployeeQueryParams(DEFAULT_EMPLOYEE_QUERY)).toEqual({});
    });

    it('writes only what differs from the default', () => {
      expect(toEmployeeQueryParams(query({ q: 'asha' }))).toEqual({ q: 'asha' });
      expect(toEmployeeQueryParams(query({ status: 'ALL' }))).toEqual({ status: 'ALL' });
      expect(toEmployeeQueryParams(query({ size: 50 }))).toEqual({ size: 50 });
      expect(toEmployeeQueryParams(query({ sort: 'lastName' }))).toEqual({ sort: 'lastName' });
      expect(toEmployeeQueryParams(query({ direction: 'desc' }))).toEqual({ direction: 'desc' });
    });

    it('writes the page 1-based, and omits the first page', () => {
      // Bracket access because the return type is a parameter map, not a known shape.
      expect(toEmployeeQueryParams(query({ page: 0 }))['page']).toBeUndefined();
      expect(toEmployeeQueryParams(query({ page: 1 }))['page']).toBe(2);
      expect(toEmployeeQueryParams(query({ page: 11 }))['page']).toBe(12);
    });

    it('omits a filter that is not set, rather than writing it blank', () => {
      const params = toEmployeeQueryParams(query({ departmentId: 1 }));

      expect(params).toEqual({ departmentId: 1 });
      expect('designationId' in params).toBe(false);
      expect('gradeId' in params).toBe(false);
    });

    it('drops a search term that is only whitespace', () => {
      expect(toEmployeeQueryParams(query({ q: '   ' }))).toEqual({});
    });
  });

  describe('round-tripping', () => {
    it('survives a full query unchanged', () => {
      const original = query({
        q: 'asha',
        departmentId: 1,
        designationId: 20,
        gradeId: 30,
        status: 'ALL',
        page: 4,
        size: 50,
        sort: 'grade',
        direction: 'desc',
      });

      const params = toEmployeeQueryParams(original);
      const restored = parseEmployeeQuery(
        new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])),
      );

      expect(restored).toEqual(original);
    });

    it('survives the default query unchanged', () => {
      expect(parseEmployeeQuery(new URLSearchParams(''))).toEqual(DEFAULT_EMPLOYEE_QUERY);
    });
  });

  /**
   * The equality function guards against a duplicate request. The router re-emits its
   * parameter map on every navigation and each parse allocates a new object, so reference
   * equality would refetch on any navigation at all.
   */
  describe('comparing two queries', () => {
    it('treats separately parsed but identical queries as equal', () => {
      expect(employeeQueriesEqual(parse('q=asha&page=2'), parse('q=asha&page=2'))).toBe(true);
    });

    it('treats the default query as equal to itself parsed from an empty URL', () => {
      expect(employeeQueriesEqual(DEFAULT_EMPLOYEE_QUERY, parse(''))).toBe(true);
    });

    it('notices a difference in any field', () => {
      const base = query();
      const variants: Partial<EmployeeQuery>[] = [
        { q: 'asha' },
        { departmentId: 1 },
        { designationId: 1 },
        { gradeId: 1 },
        { status: 'ALL' },
        { page: 1 },
        { size: 50 },
        { sort: 'lastName' },
        { direction: 'desc' },
      ];

      for (const variant of variants) {
        expect(employeeQueriesEqual(base, { ...base, ...variant })).toBe(false);
      }
    });
  });
});
