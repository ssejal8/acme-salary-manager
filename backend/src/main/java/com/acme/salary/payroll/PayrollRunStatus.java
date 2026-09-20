package com.acme.salary.payroll;

/**
 * Where a payroll run is in its life (FR-5.5).
 *
 * <p>Three states and only two transitions: {@code DRAFT → FINALISED} and
 * {@code DRAFT → CANCELLED}. Both targets are terminal, which is the whole point of
 * ADR-010 — a finalised run is immutable, so a correction is a cancelled run and a fresh
 * one rather than an edit.
 *
 * <p>The database holds the same three values in {@code ck_payroll_runs_status}, and the
 * partial unique index on the period counts only DRAFT and FINALISED — so a cancelled run
 * leaves the period free to be run again (FR-5.7).
 */
public enum PayrollRunStatus {

    /** Computed and reviewable. Payslips exist but are not published (FR-5.6, FR-5.8). */
    DRAFT,

    /** Published and immutable. Payslips are visible to the employees concerned. */
    FINALISED,

    /** Abandoned. Its payslips stand as a record of what was computed, unpublished. */
    CANCELLED;

    public boolean isTerminal() {
        return this != DRAFT;
    }
}
