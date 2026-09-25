package com.acme.salary.payroll;

/**
 * What a payslip list is being asked for (FR-6.5, FR-7.1).
 *
 * <p>Every field is optional and {@code null} means "do not filter on this", which is what
 * lets one query serve three screens: the payslips of one run (the register for a period),
 * an HR search across periods and departments, and the paged table a draft run is reviewed
 * in.
 *
 * @param publishedOnly when true, only payslips whose run is finalised. Not a filter a
 *     client chooses — it is set by the service from the caller's role, because a draft
 *     payslip has not been published and an employee must never see one (FR-5.8). Keeping
 *     it in this record rather than in a second query means the restriction is applied in
 *     the database, where it cannot be forgotten by a later caller.
 */
public record PayslipSearch(
        Long runId,
        Integer periodYear,
        Integer periodMonth,
        Long departmentId,
        Long employeeId,
        boolean publishedOnly) {

    /** Every payslip in one run — the review table, and the register for its period. */
    public static PayslipSearch forRun(Long runId) {
        return new PayslipSearch(runId, null, null, null, null, false);
    }

    /**
     * Whether a period was given in full.
     *
     * <p>A month without a year is not a period — "March" of no particular year would
     * match every March on record — so the two are only honoured together.
     */
    public boolean hasCompletePeriod() {
        return periodYear != null && periodMonth != null;
    }

    /**
     * The same search, pinned to one employee and to published payslips.
     *
     * <p>Deliberately not named {@code publishedOnly}: a no-argument method of that name
     * would replace the record's own accessor for the component, which is the kind of
     * quiet substitution that compiles and then confuses everything downstream.
     */
    public PayslipSearch restrictedTo(Long onlyEmployeeId) {
        return new PayslipSearch(runId, periodYear, periodMonth, departmentId, onlyEmployeeId, true);
    }
}
