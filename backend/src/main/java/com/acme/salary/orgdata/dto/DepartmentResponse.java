package com.acme.salary.orgdata.dto;

import com.acme.salary.orgdata.Department;

/**
 * A department as the API exposes it (FR-3.1).
 *
 * <p>{@code headEmployeeId} is deliberately absent. Resolving it would make this feature
 * read the employee feature, and reference data does not point back at employees
 * (architecture §4.2) — the seam that keeps {@code orgdata} independent.
 */
public record DepartmentResponse(Long id, String code, String name) {

    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getCode(), department.getName());
    }
}
