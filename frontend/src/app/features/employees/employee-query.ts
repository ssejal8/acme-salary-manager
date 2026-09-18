import {
  DEFAULT_EMPLOYEE_QUERY,
  EMPLOYEE_SORT_KEYS,
  EmployeeQuery,
  EmployeeSortKey,
  PAGE_SIZE_OPTIONS,
  SORT_DIRECTIONS,
  STATUS_FILTERS,
  SortDirection,
  StatusFilter,
} from './employee.models';

/**
 * Translation between the employee list's state and the URL query string.
 *
 * Kept out of the component for two reasons. It is the part most worth testing directly —
 * every rule below exists because of something the server or a user does — and it makes
 * the URL the component's single source of truth rather than a copy it has to keep in
 * step.
 *
 * ## Reading is defensive, because a URL is untrusted input
 *
 * A query string can be hand-edited, bookmarked from an older version of this screen, or
 * mangled by a chat client that ate an escape. None of that may produce a broken screen or
 * a bad request, so every value is validated and anything unrecognised falls back to its
 * default rather than being passed through.
 *
 * The sort key matters most: the API answers **400** for a key outside its whitelist rather
 * than ignoring it, so forwarding `?sort=passwordHash` would turn a stale bookmark into an
 * error page instead of a list.
 *
 * ## Writing omits defaults
 *
 * A freshly opened list is `/employees`, not
 * `/employees?status=ACTIVE_ONLY&page=0&size=20&sort=employeeCode&direction=asc`. Only what
 * differs from the default is written, which is what makes a shared link readable and
 * keeps the common case clean.
 */

/**
 * The read side of a parameter source.
 *
 * Angular's `ParamMap` and the platform's `URLSearchParams` both satisfy this
 * structurally, so the component can pass the router's map and a test can pass a
 * `URLSearchParams` built from a literal query string — no adapter, and no Angular import
 * in this file.
 */
export interface ReadonlyParams {
  get(name: string): string | null;
}

/** Query-string keys, named once so the parse and serialise sides cannot disagree. */
const PARAM = {
  q: 'q',
  departmentId: 'departmentId',
  designationId: 'designationId',
  gradeId: 'gradeId',
  status: 'status',
  page: 'page',
  size: 'size',
  sort: 'sort',
  direction: 'direction',
} as const;

/** What `router.navigate` accepts for `queryParams`. */
export type EmployeeQueryParams = Record<string, string | number>;

/** Builds a query from a URL, falling back to a default for anything unusable. */
export function parseEmployeeQuery(params: ReadonlyParams): EmployeeQuery {
  return {
    q: parseSearchTerm(params.get(PARAM.q)),
    departmentId: parseReferenceId(params.get(PARAM.departmentId)),
    designationId: parseReferenceId(params.get(PARAM.designationId)),
    gradeId: parseReferenceId(params.get(PARAM.gradeId)),
    status: parseOneOf<StatusFilter>(
      params.get(PARAM.status),
      STATUS_FILTERS,
      DEFAULT_EMPLOYEE_QUERY.status,
    ),
    page: parsePage(params.get(PARAM.page)),
    size: parsePageSize(params.get(PARAM.size)),
    sort: parseOneOf<EmployeeSortKey>(
      params.get(PARAM.sort),
      EMPLOYEE_SORT_KEYS,
      DEFAULT_EMPLOYEE_QUERY.sort,
    ),
    direction: parseOneOf<SortDirection>(
      params.get(PARAM.direction),
      SORT_DIRECTIONS,
      DEFAULT_EMPLOYEE_QUERY.direction,
    ),
  };
}

/**
 * Renders a query as query parameters, omitting everything left at its default.
 *
 * The result is passed to `router.navigate` *without* `queryParamsHandling`, so it replaces
 * the query string wholesale — which is how an omitted key becomes a removed one rather
 * than a stale one.
 */
export function toEmployeeQueryParams(query: EmployeeQuery): EmployeeQueryParams {
  const params: EmployeeQueryParams = {};

  const term = query.q?.trim();
  if (term) {
    params[PARAM.q] = term;
  }
  if (query.departmentId !== undefined) {
    params[PARAM.departmentId] = query.departmentId;
  }
  if (query.designationId !== undefined) {
    params[PARAM.designationId] = query.designationId;
  }
  if (query.gradeId !== undefined) {
    params[PARAM.gradeId] = query.gradeId;
  }
  if (query.status !== DEFAULT_EMPLOYEE_QUERY.status) {
    params[PARAM.status] = query.status;
  }
  // Page is 1-based in the URL and 0-based in the API. A link that reads `page=2` and
  // shows the third page would be a small, permanent confusion for anyone sharing one.
  if (query.page > 0) {
    params[PARAM.page] = query.page + 1;
  }
  if (query.size !== DEFAULT_EMPLOYEE_QUERY.size) {
    params[PARAM.size] = query.size;
  }
  if (query.sort !== DEFAULT_EMPLOYEE_QUERY.sort) {
    params[PARAM.sort] = query.sort;
  }
  if (query.direction !== DEFAULT_EMPLOYEE_QUERY.direction) {
    params[PARAM.direction] = query.direction;
  }
  return params;
}

/**
 * Whether two queries would fetch the same page.
 *
 * Used as the equality function on the component's derived criteria. The router re-emits
 * its parameter map on every successful navigation, and each parse produces a fresh object,
 * so without this a navigation that changed nothing relevant would still trigger a second
 * identical request.
 */
export function employeeQueriesEqual(a: EmployeeQuery, b: EmployeeQuery): boolean {
  return (
    a.q === b.q &&
    a.departmentId === b.departmentId &&
    a.designationId === b.designationId &&
    a.gradeId === b.gradeId &&
    a.status === b.status &&
    a.page === b.page &&
    a.size === b.size &&
    a.sort === b.sort &&
    a.direction === b.direction
  );
}

function parseSearchTerm(raw: string | null): string | undefined {
  const term = raw?.trim();
  return term ? term : undefined;
}

/**
 * A reference-data id: a positive integer, or nothing.
 *
 * Anything else becomes `undefined` rather than being forwarded. `?departmentId=abc` would
 * otherwise reach the server as a value that fails to bind to a `Long`, turning a mistyped
 * URL into a 400 instead of an unfiltered list.
 */
function parseReferenceId(raw: string | null): number | undefined {
  if (raw === null || raw.trim() === '') {
    return undefined;
  }
  const value = Number(raw);
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

/** The URL's 1-based page, as the API's 0-based one. Out-of-range values mean the first. */
function parsePage(raw: string | null): number {
  if (raw === null) {
    return 0;
  }
  const value = Number(raw);
  return Number.isInteger(value) && value >= 1 ? value - 1 : 0;
}

/**
 * A page size from the offered set, or the default.
 *
 * Deliberately not clamped to the range. `?size=5000` becomes 20 rather than 100, because
 * the selector could not show 100 as chosen without implying the user picked it — and the
 * server would clamp it anyway, leaving the control disagreeing with the response.
 */
function parsePageSize(raw: string | null): number {
  if (raw === null) {
    return DEFAULT_EMPLOYEE_QUERY.size;
  }
  const value = Number(raw);
  return (PAGE_SIZE_OPTIONS as readonly number[]).includes(value)
    ? value
    : DEFAULT_EMPLOYEE_QUERY.size;
}

/** Narrows a raw value to one of a closed set, or returns the fallback. */
function parseOneOf<T extends string>(
  raw: string | null,
  allowed: readonly T[],
  fallback: T,
): T {
  return raw !== null && (allowed as readonly string[]).includes(raw) ? (raw as T) : fallback;
}
