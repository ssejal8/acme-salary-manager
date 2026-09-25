/**
 * The audit trail, mirroring `com.acme.salary.common.audit.dto.AuditEventResponse`.
 *
 * ADMIN only: one page of this spans every feature, so it is the strictest read in the API.
 */

/** The entity types the trail records, mirroring `AuditEntityType`. */
export const AUDIT_ENTITY_TYPES = [
  'Employee',
  'SalaryStructure',
  'SalaryComponent',
  'PayrollRun',
] as const;

export type AuditEntityType = (typeof AUDIT_ENTITY_TYPES)[number];

/**
 * The actions recorded, mirroring `AuditAction`.
 *
 * A closed list here and on the server, so a filter offers what exists rather than a free
 * text box that mostly returns nothing. The API matches exactly and answers an unknown
 * value with an empty page, so a new action added server-side is a missing option here
 * rather than a broken screen.
 */
export const AUDIT_ACTIONS = [
  'EMPLOYEE_CREATED',
  'EMPLOYEE_UPDATED',
  'EMPLOYEE_DEACTIVATED',
  'SALARY_COMPONENT_CREATED',
  'SALARY_STRUCTURE_ASSIGNED',
  'SALARY_STRUCTURE_SUPERSEDED',
  'PAYROLL_RUN_CREATED',
  'PAYROLL_RUN_FINALISED',
  'PAYROLL_RUN_CANCELLED',
] as const;

export type AuditAction = (typeof AUDIT_ACTIONS)[number];

/** One recorded action: who, what, when. */
export interface AuditEvent {
  id: number;
  /** An ISO instant, with a zone — unlike the calendar dates elsewhere in this API. */
  occurredAt: string;
  /** Absent for a system-initiated action. */
  actorUserId?: number;
  /** Absent for a system action, and for an actor whose login no longer exists. */
  actorEmail?: string;
  entityType: string;
  entityId?: number;
  action: string;
  /** The context recorded with the action, already parsed. Absent when none was. */
  details?: Record<string, unknown>;
}

/** What the screen sends to `GET /audit-events`. Every filter is optional. */
export interface AuditQuery {
  actorUserId?: number;
  entityType?: string;
  entityId?: number;
  action?: string;
  /** Calendar days, inclusive at both ends, interpreted as UTC by the server. */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}
