package com.acme.salary.employee;

/**
 * Filter criteria for an employee list query (FR-2.4). Every field is optional except the
 * status filter, which is deliberately not nullable.
 *
 * <p>Soft deletion means an unfiltered query silently includes leavers — the cost ADR-014
 * accepts. Making the caller name a {@link StatusFilter} turns that omission into a
 * decision someone has to write down.
 *
 * @param nameQuery matched case-insensitively against "first last"
 * @param departmentId exact match, or null for any department
 * @param designationId exact match, or null for any designation
 * @param gradeId exact match, or null for any grade
 * @param statusFilter which employment statuses to include; never null
 */
public record EmployeeSearch(
        String nameQuery,
        Long departmentId,
        Long designationId,
        Long gradeId,
        StatusFilter statusFilter) {

    public enum StatusFilter {
        /** The default for every HR-facing list: current staff only. */
        ACTIVE_ONLY,
        /** Leavers only, for exit reporting. */
        INACTIVE_ONLY,
        /** Both. Has to be asked for explicitly. */
        ALL
    }

    public EmployeeSearch {
        if (statusFilter == null) {
            statusFilter = StatusFilter.ACTIVE_ONLY;
        }
        if (nameQuery != null && nameQuery.isBlank()) {
            nameQuery = null;
        }
    }

    /** Active employees, unfiltered otherwise. */
    public static EmployeeSearch activeEmployees() {
        return new EmployeeSearch(null, null, null, null, StatusFilter.ACTIVE_ONLY);
    }

    public EmployeeSearch withStatusFilter(StatusFilter statusFilter) {
        return new EmployeeSearch(nameQuery, departmentId, designationId, gradeId, statusFilter);
    }
}
