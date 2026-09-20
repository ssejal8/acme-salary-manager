package com.acme.salary.employee;

/**
 * Who an employee is, for a document that has to name them.
 *
 * <p>The employee feature's published port for identity, along
 * {@link EmployeeCompensationContext} for pay decisions. Two records rather than one
 * because they answer different questions: a payslip header needs a name and a job title
 * and has no business knowing a grade's CTC band, while a structure assignment needs the
 * band and does not care about the job title.
 *
 * <p>Carries no compensation. A payslip's figures come from the payslip, which is the
 * record of what was actually paid — reading them from the employee's current package
 * would make a historical payslip change when someone gets a raise.
 */
public record EmployeeIdentity(
        Long employeeId,
        String employeeCode,
        String fullName,
        String workEmail,
        String departmentName,
        String designationTitle,
        String gradeName) {

    static EmployeeIdentity from(Employee employee) {
        return new EmployeeIdentity(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.fullName(),
                employee.getWorkEmail(),
                employee.getDepartment().getName(),
                employee.getDesignation().getTitle(),
                employee.getGrade().getName());
    }
}
