package com.acme.salary.orgdata;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByCode(String code);

    /**
     * Uniqueness pre-check so a duplicate yields a clean 409 with a field message
     * (FR-2.2 style). The unique index is what actually prevents the duplicate under a
     * race; this is for the error message (architecture §7.2).
     */
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    List<Department> findAllByOrderByNameAsc();
}
