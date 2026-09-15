package com.acme.salary.employee;

/**
 * Employment status. Records are never hard-deleted (ADR-014): leaving sets
 * {@link #INACTIVE} plus an exit date, which is what payroll eligibility is derived from
 * (FR-2.6).
 */
public enum EmployeeStatus {
    ACTIVE,
    INACTIVE
}
