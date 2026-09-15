package com.acme.salary.orgdata;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An organisational unit an employee belongs to (FR-3.1).
 *
 * <p>A department in use cannot be deleted; the database enforces that with
 * {@code ON DELETE RESTRICT} rather than leaving it to a service check (FR-3.3).
 */
@Entity
@Table(name = "departments")
public class Department extends AuditableEntity {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /**
     * The department head, held as an id rather than an association.
     *
     * <p>Reference data must not depend on the employee feature: an association here
     * would make {@code orgdata} and {@code employee} mutually dependent and close the
     * extraction seam ADR-001 relies on. Employees point at departments; departments do
     * not point back.
     */
    @Column(name = "head_employee_id")
    private Long headEmployeeId;

    protected Department() {
        // for JPA
    }

    public Department(String code, String name) {
        this.code = requireCode(code);
        this.name = requireName(name);
    }

    public void rename(String name) {
        this.name = requireName(name);
    }

    public void assignHead(Long employeeId) {
        this.headEmployeeId = employeeId;
    }

    public void clearHead() {
        this.headEmployeeId = null;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw ValidationException.field("code", "must not be blank");
        }
        // Codes are referenced in reports and exports, so they are stored uppercased to
        // keep a single canonical form.
        return code.strip().toUpperCase();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ValidationException.field("name", "must not be blank");
        }
        return name.strip();
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Long getHeadEmployeeId() {
        return headEmployeeId;
    }
}
