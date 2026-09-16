package com.acme.salary.salarycomponent;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryComponentRepository extends JpaRepository<SalaryComponent, Long> {

    Optional<SalaryComponent> findByCode(String code);

    boolean existsByCode(String code);

    List<SalaryComponent> findAllByOrderByTypeAscCodeAsc();

    List<SalaryComponent> findAllByActiveTrueOrderByTypeAscCodeAsc();

    /**
     * Loads the definitions a structure references in one query rather than one per
     * component — the same batch-read habit the payroll run depends on
     * (architecture §5.3).
     */
    List<SalaryComponent> findAllByIdIn(Collection<Long> ids);
}
