package com.acme.salary.orgdata;

import com.acme.salary.orgdata.dto.DepartmentResponse;
import com.acme.salary.orgdata.dto.DesignationResponse;
import com.acme.salary.orgdata.dto.GradeResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference-data reads (FR-3.1, FR-3.2).
 *
 * <p>Unpaged on purpose. These are three closed, small lists — an organisation has tens of
 * departments, not thousands — and their only consumer is a set of filter and form
 * dropdowns, which need the whole list or none of it. Paging them would add a contract
 * every caller has to loop over for no benefit; the page-size cap in NFR-1.2 exists for
 * lists that grow with headcount, and these do not.
 *
 * <p>Each list is returned in the order a dropdown wants to display it, sorted in the
 * database rather than in Java.
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
}
