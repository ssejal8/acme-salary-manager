package com.acme.salary.orgdata;

import com.acme.salary.common.error.ConflictException;
import com.acme.salary.common.error.NotFoundException;
import com.acme.salary.orgdata.dto.CreateDepartmentRequest;
import com.acme.salary.orgdata.dto.DepartmentResponse;
import com.acme.salary.orgdata.dto.DesignationResponse;
import com.acme.salary.orgdata.dto.GradeResponse;
import com.acme.salary.orgdata.dto.SaveDesignationRequest;
import com.acme.salary.orgdata.dto.SaveGradeRequest;
import com.acme.salary.orgdata.dto.UpdateDepartmentRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference data: the vocabulary an employee record is expressed in (FR-3.1 to FR-3.3).
 *
 * <p>Unpaged on purpose. These are three closed, small lists — an organisation has tens of
 * departments, not thousands — and their only consumer is a set of filter and form
 * dropdowns, which need the whole list or none of it. Paging them would add a contract
 * every caller has to loop over for no benefit; the page-size cap in NFR-1.2 exists for
 * lists that grow with headcount, and these do not.
 *
 * <p>Each list is returned in the order a dropdown wants to display it, sorted in the
 * database rather than in Java.
 *
 * <h2>Writes are ADMIN's, and there are no deletions</h2>
 *
 * <p>Adding a department changes the vocabulary every employee record and every report is
 * expressed in, which is why the endpoints are {@code hasRole('ADMIN')} while reading them
 * is open to HR — a form cannot be filled in without the lists it offers.
 *
 * <p>There is no delete, and it is not an omission. Employees reference these rows with
 * {@code ON DELETE RESTRICT}, so a department in use cannot be removed at all, and one
 * that is not in use is harmless. Removing a row would also orphan the history that names
 * it: a payslip from two years ago belongs to whoever was in Engineering then.
 *
 * <p>These writes record no audit event. FR-8.1 asks for a trail over employees, salary
 * structures and payroll runs — the things that decide what people are paid — and
 * extending it here would be a guess at a requirement rather than a reading of one. The
 * {@code created_at}/{@code updated_at} columns on each row remain.
 */
@Service
public class ReferenceDataService {

    private final DepartmentRepository departments;
    private final DesignationRepository designations;
    private final GradeRepository grades;

    public ReferenceDataService(
            DepartmentRepository departments,
            DesignationRepository designations,
            GradeRepository grades) {
        this.departments = departments;
        this.designations = designations;
        this.grades = grades;
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> departments() {
        return departments.findAllByOrderByNameAsc().stream().map(DepartmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DesignationResponse> designations() {
        return designations.findAllByOrderByTitleAsc().stream().map(DesignationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GradeResponse> grades() {
        return grades.findAllByOrderByNameAsc().stream().map(GradeResponse::from).toList();
    }

    /**
     * Adds a department (FR-3.1).
     *
     * <p>Both the code and the name are checked for duplicates, and the code
     * case-insensitively via the entity's own normalisation, because {@code ENG} and
     * {@code eng} must not become two departments that read the same in every dropdown.
     */
    @Transactional
    public DepartmentResponse createDepartment(CreateDepartmentRequest request) {
        String code = request.code().strip().toUpperCase();
        if (departments.existsByCode(code)) {
            throw ConflictException.field("code", "a department with code " + code + " already exists");
        }
        return DepartmentResponse.from(
                departments.save(new Department(code, request.name())));
    }

    /** Renames a department. The code is immutable after creation (see the class note). */
    @Transactional
    public DepartmentResponse renameDepartment(Long id, UpdateDepartmentRequest request) {
        Department department = departments.findById(id)
                .orElseThrow(() -> NotFoundException.of("Department", id));
        department.rename(request.name());
        return DepartmentResponse.from(department);
    }

    /** Adds a designation (FR-3.2). */
    @Transactional
    public DesignationResponse createDesignation(SaveDesignationRequest request) {
        String title = request.title().strip();
        if (designations.existsByTitleIgnoreCase(title)) {
            throw ConflictException.field("title", "a designation titled " + title + " already exists");
        }
        return DesignationResponse.from(designations.save(new Designation(title)));
    }

    /**
     * Retitles a designation.
     *
     * <p>The uniqueness check excludes this row, so saving a form that did not change the
     * title is not a conflict with itself — the same reason the employee email check does.
     */
    @Transactional
    public DesignationResponse retitleDesignation(Long id, SaveDesignationRequest request) {
        Designation designation = designations.findById(id)
                .orElseThrow(() -> NotFoundException.of("Designation", id));
        String title = request.title().strip();
        if (designations.existsByTitleIgnoreCaseAndIdNot(title, id)) {
            throw ConflictException.field("title", "a designation titled " + title + " already exists");
        }
        designation.retitle(title);
        return DesignationResponse.from(designation);
    }

    /**
     * Adds a grade with its CTC band (FR-3.3).
     *
     * <p>The band itself is validated by the entity, which owns the rule that a maximum
     * cannot sit below a minimum — and an absent bound stays absent, meaning unbounded on
     * that side rather than zero.
     */
    @Transactional
    public GradeResponse createGrade(SaveGradeRequest request) {
        String name = request.name().strip();
        if (grades.existsByNameIgnoreCase(name)) {
            throw ConflictException.field("name", "a grade named " + name + " already exists");
        }
        return GradeResponse.from(
                grades.save(new Grade(name, request.minCtc(), request.maxCtc())));
    }

    /**
     * Amends a grade's name or band.
     *
     * <p>Widening or narrowing a band does not re-validate the packages already assigned
     * against it, and that is deliberate: a salary structure is a historical record of
     * what was agreed (ADR-009), so a band change applies to the next assignment rather
     * than retroactively invalidating existing pay. The compensation screens show the
     * current band beside the current package, which is where the discrepancy becomes
     * visible to a person.
     */
    @Transactional
    public GradeResponse updateGrade(Long id, SaveGradeRequest request) {
        Grade grade = grades.findById(id)
                .orElseThrow(() -> NotFoundException.of("Grade", id));
        String name = request.name().strip();
        if (grades.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw ConflictException.field("name", "a grade named " + name + " already exists");
        }
        grade.rename(name);
        grade.setBand(request.minCtc(), request.maxCtc());
        return GradeResponse.from(grade);
    }
}
