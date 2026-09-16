package com.acme.salary.salarycomponent;

/**
 * How a component's amount is arrived at.
 *
 * <p>A closed set of two by decision (ADR-016). No user-authored formula is ever
 * evaluated: every possible calculation in the system is enumerable here and unit-tested,
 * and nothing a user types becomes code inside the payroll process.
 */
public enum CalculationType {

    /** The configured value is the monthly amount. */
    FLAT,

    /** The configured value is a percentage of the basic component. */
    PERCENT_OF_BASIC
}
