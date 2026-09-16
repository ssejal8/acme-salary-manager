package com.acme.salary.common.audit;

/** Entity type names used in the audit trail. */
public final class AuditEntityType {

    public static final String EMPLOYEE = "Employee";
    public static final String SALARY_COMPONENT = "SalaryComponent";
    public static final String SALARY_STRUCTURE = "SalaryStructure";
    public static final String PAYROLL_RUN = "PayrollRun";

    private AuditEntityType() {
    }
}
