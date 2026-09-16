import { Reference } from '../../shared/page-response';

/** Mirrors `com.acme.salary.employee.EmployeeStatus`. */
export type EmployeeStatus = 'ACTIVE' | 'INACTIVE';

/**
 * Which employment statuses a list query includes.
 *
 * `ACTIVE_ONLY` is the server's default and has to be overridden explicitly to see
 * leavers: records are soft-deleted (ADR-014), so an unfiltered list would quietly include
 * them.
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
 * The sort keys the API accepts.
 *
 * This list is not decoration. The server rejects anything outside its whitelist with a
 * 400 rather than ignoring it (it would otherwise reach the query as a property path), so
 * a typo in a column header is a failed request — worth catching in the type system.
 */
export type EmployeeSortKey =
  | 'employeeCode'
  | 'firstName'
  | 'lastName'
  | 'workEmail'
  | 'dateOfJoining'
  | 'exitDate'
  | 'status'
  | 'department'
  | 'designation'
  | 'grade';

export type SortDirection = 'asc' | 'desc';

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

/** The server's own defaults: active staff, by employee code. */
export const DEFAULT_EMPLOYEE_QUERY: EmployeeQuery = {
  status: 'ACTIVE_ONLY',
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  sort: 'employeeCode',
  direction: 'asc',
};
