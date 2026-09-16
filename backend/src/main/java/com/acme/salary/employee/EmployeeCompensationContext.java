package com.acme.salary.employee;

import com.acme.salary.orgdata.CtcBand;
import java.time.LocalDate;

/**
 * What another feature needs to know about an employee before touching their pay.
 *
 * <p>This is the employee feature's published port for compensation decisions: the salary
 * structure feature validates an effective date against the joining date (FR-4.6) and a
 * proposed package against the grade band (FR-4.3) without ever holding an
 * {@link Employee} entity (ADR-001).
 */
public record EmployeeCompensationContext(
        Long employeeId,
        String employeeCode,
        String fullName,
        LocalDate dateOfJoining,
        LocalDate exitDate,
        EmployeeStatus status,
        Long gradeId,
        String gradeName,
        CtcBand gradeBand) {

    static EmployeeCompensationContext from(Employee employee) {
        return new EmployeeCompensationContext(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.fullName(),
                employee.getDateOfJoining(),
                employee.getExitDate(),
                employee.getStatus(),
                employee.getGrade().getId(),
                employee.getGrade().getName(),
                employee.getGrade().band());
    }

    public boolean isActive() {
        return status == EmployeeStatus.ACTIVE;
    }
}
