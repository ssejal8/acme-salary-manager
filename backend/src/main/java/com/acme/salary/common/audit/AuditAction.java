package com.acme.salary.common.audit;

/**
 * The business actions this system records. A constant rather than a free string, so the
 * audit trail stays queryable and a typo cannot create a second kind of event.
 */
public final class AuditAction {

    public static final String EMPLOYEE_CREATED = "EMPLOYEE_CREATED";
    public static final String EMPLOYEE_UPDATED = "EMPLOYEE_UPDATED";
    public static final String EMPLOYEE_DEACTIVATED = "EMPLOYEE_DEACTIVATED";
    public static final String SALARY_COMPONENT_CREATED = "SALARY_COMPONENT_CREATED";
    public static final String SALARY_STRUCTURE_ASSIGNED = "SALARY_STRUCTURE_ASSIGNED";
    public static final String SALARY_STRUCTURE_SUPERSEDED = "SALARY_STRUCTURE_SUPERSEDED";
    public static final String PAYROLL_RUN_CREATED = "PAYROLL_RUN_CREATED";
    public static final String PAYROLL_RUN_FINALISED = "PAYROLL_RUN_FINALISED";
    public static final String PAYROLL_RUN_CANCELLED = "PAYROLL_RUN_CANCELLED";

    private AuditAction() {
    }
}
