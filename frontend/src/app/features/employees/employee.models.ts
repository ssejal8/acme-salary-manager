import { Reference } from '../../shared/page-response';

/** Mirrors `com.acme.salary.employee.EmployeeStatus`. */
export type EmployeeStatus = 'ACTIVE' | 'INACTIVE';

/**
 * Which employment statuses a list query includes.
 *
 * `ACTIVE_ONLY` is the server's default and has to be overridden explicitly to see
 * leavers: records are soft-deleted (ADR-014), so an unfiltered list would quietly include
 * them.
 *
 * The runtime values are in {@link STATUS_FILTERS}, declared below the sort keys.
 */
export type StatusFilter = 'ACTIVE_ONLY' | 'INACTIVE_ONLY' | 'ALL';

/**
 * One row of the employee list.
 *
 * Carries no compensation data, and that is the API's decision rather than an omission
 * here: salary figures have no business in a list payload (NFR-2.7).
 */
export interface EmployeeSummary {
  id: number;
  employeeCode: string;
  firstName: string;
  lastName: string;
  fullName: string;
  workEmail: string;
  dateOfJoining: string;
  /** Present only for a leaver. */
  exitDate?: string;
  status: EmployeeStatus;
  department: Reference;
  designation: Reference;
  grade: Reference;
}

/**
 * The sort keys the API accepts, mirroring `EmployeeController.SORTABLE_PROPERTIES`.
 *
 * This list is not decoration. The server rejects anything outside its whitelist with a
 * 400 rather than ignoring it — an unlisted key would otherwise reach the query as a
 * property path — so a bad key is a failed request, not a mis-sorted list.
 *
 * Declared as a runtime array with the type derived from it, rather than as a union type,
 * because both are needed: the type for compile-time safety, and the values to validate a
 * sort key arriving from the URL, which no type can check.
 */
export const EMPLOYEE_SORT_KEYS = [
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
] as const;

export type EmployeeSortKey = (typeof EMPLOYEE_SORT_KEYS)[number];

export const SORT_DIRECTIONS = ['asc', 'desc'] as const;

export type SortDirection = (typeof SORT_DIRECTIONS)[number];

export const STATUS_FILTERS = ['ACTIVE_ONLY', 'INACTIVE_ONLY', 'ALL'] as const;

/** Everything the list screen sends to `GET /employees`. */
export interface EmployeeQuery {
  /** Case-insensitive match against the full name. */
  q?: string;
  departmentId?: number;
  designationId?: number;
  gradeId?: number;
  status: StatusFilter;
  /** Zero-based. */
  page: number;
  /** Capped at 100 by the server. */
  size: number;
  sort: EmployeeSortKey;
  direction: SortDirection;
}

export const DEFAULT_PAGE_SIZE = 20;

/**
 * The server caps page size at 100 (`PageableSanitizer.MAX_PAGE_SIZE`), silently, by
 * clamping rather than rejecting. The selector therefore stops at 100: offering more would
 * show a size the response then contradicts.
 */
export const MAX_PAGE_SIZE = 100;

/**
 * Rows-per-page choices.
 *
 * A closed set rather than a free number, so `?size=` from a URL can be validated against
 * something. 100 is the server's ceiling; 10 exists because the filters are most useful
 * when the result is small enough to read at a glance.
 */
export const PAGE_SIZE_OPTIONS = [10, 20, 50, MAX_PAGE_SIZE] as const;

/** The server's own defaults: active staff, by employee code. */
export const DEFAULT_EMPLOYEE_QUERY: EmployeeQuery = {
  status: 'ACTIVE_ONLY',
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  sort: 'employeeCode',
  direction: 'asc',
};
