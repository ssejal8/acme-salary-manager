package com.acme.salary.employee;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.persistence.AuditableEntity;
import com.acme.salary.orgdata.Department;
import com.acme.salary.orgdata.Designation;
import com.acme.salary.orgdata.Grade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A salaried member of staff.
 *
 * <p>Two fields are immutable after creation (FR-2.3): the employee code, which appears
 * on published payslips, and the date of joining, which every effective-dated salary
 * structure is validated against. Both are mapped {@code updatable = false}, so the
 * mapping enforces it rather than relying on a service remembering to.
 *
 * <p>Reference data is fetched lazily and associated by entity, because employee lists
 * filter and sort by department, designation and grade (FR-2.4). The link to the
 * authentication feature is deliberately different: {@code userId} is a plain id, not an
 * association, so this package does not depend on {@code security} — the boundary ADR-001
 * exists to preserve.
 */
@Entity
@Table(name = "employees")
public class Employee extends AuditableEntity {

    @Column(name = "employee_code", nullable = false, length = 20, updatable = false)
    private String employeeCode;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Column(name = "work_email", nullable = false, length = 255)
    private String workEmail;

    @Column(name = "date_of_joining", nullable = false, updatable = false)
    private LocalDate dateOfJoining;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmployeeStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "designation_id", nullable = false)
    private Designation designation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grade_id", nullable = false)
    private Grade grade;

    @Column(name = "user_id")
    private Long userId;

    protected Employee() {
        // for JPA
    }

    public Employee(
            String employeeCode,
            String firstName,
            String lastName,
            String workEmail,
            LocalDate dateOfJoining,
            Department department,
            Designation designation,
            Grade grade) {
        this.employeeCode = requireText("employeeCode", employeeCode, 20).toUpperCase();
        this.firstName = requireText("firstName", firstName, 80);
        this.lastName = requireText("lastName", lastName, 80);
        this.workEmail = requireEmail(workEmail);
        this.dateOfJoining = requireNonNull("dateOfJoining", dateOfJoining);
        this.department = requireNonNull("departmentId", department);
        this.designation = requireNonNull("designationId", designation);
        this.grade = requireNonNull("gradeId", grade);
        this.status = EmployeeStatus.ACTIVE;
    }

    /** Updates the editable fields. Code and date of joining are not among them. */
    public void updateDetails(
            String firstName,
            String lastName,
            String workEmail,
            Department department,
            Designation designation,
            Grade grade) {
        this.firstName = requireText("firstName", firstName, 80);
        this.lastName = requireText("lastName", lastName, 80);
        this.workEmail = requireEmail(workEmail);
        this.department = requireNonNull("departmentId", department);
        this.designation = requireNonNull("designationId", designation);
        this.grade = requireNonNull("gradeId", grade);
    }

    /**
     * Records an exit and deactivates (FR-2.5). There is no delete: a payslip from years
     * ago must still resolve its employee (ADR-014).
     */
    public void deactivate(LocalDate exitDate) {
        LocalDate exit = requireNonNull("exitDate", exitDate);
        if (exit.isBefore(dateOfJoining)) {
            throw ValidationException.field("exitDate", "must not precede the date of joining");
        }
        this.exitDate = exit;
        this.status = EmployeeStatus.INACTIVE;
    }

    /** Reverses a deactivation, for the re-hire and mistaken-exit cases. */
    public void reactivate() {
        this.exitDate = null;
        this.status = EmployeeStatus.ACTIVE;
    }

    /**
     * Whether this employee belongs in a payroll run for the given period.
     *
     * <p>Two conditions, from FR-2.6 and FR-5.2: they must have joined on or before the
     * period ends, and — if they have left — their exit date must not fall before the
     * period begins. An active employee with no exit date always qualifies once joined.
     *
     * <p>Whether they also hold an effective salary structure is the salary structure
     * feature's question, not this one's.
     */
    public boolean isEligibleForPayroll(LocalDate periodStart, LocalDate periodEnd) {
        if (dateOfJoining.isAfter(periodEnd)) {
            return false;
        }
        return exitDate == null || !exitDate.isBefore(periodStart);
    }

    /** Links the login provisioned for this employee (FR-2.7). */
    public void linkUser(Long userId) {
        this.userId = requireNonNull("userId", userId);
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }

    private static String requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, "must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw ValidationException.field(field, "must be at most " + maxLength + " characters");
        }
        return stripped;
    }

    private static String requireEmail(String email) {
        String value = requireText("workEmail", email, 255);
        if (!value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ValidationException.field("workEmail", "must be a valid email address");
        }
        // Lowercased because the work email doubles as the login username (FR-2.7), and a
        // case variant must not become a second account or a second employee.
        return value.toLowerCase();
    }

    private static <T> T requireNonNull(String field, T value) {
        if (value == null) {
            throw ValidationException.field(field, "is required");
        }
        return value;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getWorkEmail() {
        return workEmail;
    }

    public LocalDate getDateOfJoining() {
        return dateOfJoining;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public Department getDepartment() {
        return department;
    }

    public Designation getDesignation() {
        return designation;
    }

    public Grade getGrade() {
        return grade;
    }

    public Long getUserId() {
        return userId;
    }
}
