package com.acme.salary.employee;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.salary.employee.EmployeeSearch.StatusFilter;
import org.junit.jupiter.api.Test;

/**
 * The search criteria default to current staff. Soft deletion makes an unfiltered list a
 * quiet bug (ADR-014), so the safe option is the one you get for free.
 */
class EmployeeSearchTest {

    @Test
    void defaultsToActiveEmployeesOnly() {
        assertThat(EmployeeSearch.activeEmployees().statusFilter()).isEqualTo(StatusFilter.ACTIVE_ONLY);
    }

    @Test
    void aNullStatusFilterBecomesActiveOnlyRatherThanEverything() {
        EmployeeSearch search = new EmployeeSearch("asha", 1L, 2L, 3L, null);

        assertThat(search.statusFilter()).isEqualTo(StatusFilter.ACTIVE_ONLY);
    }

    @Test
    void includingLeaversHasToBeStated() {
        EmployeeSearch search = EmployeeSearch.activeEmployees().withStatusFilter(StatusFilter.ALL);

        assertThat(search.statusFilter()).isEqualTo(StatusFilter.ALL);
    }

    @Test
    void blankNameQueryIsTreatedAsNoFilter() {
        assertThat(new EmployeeSearch("   ", null, null, null, StatusFilter.ALL).nameQuery()).isNull();
    }

    @Test
    void keepsTheOtherCriteriaWhenTheStatusFilterChanges() {
        EmployeeSearch search = new EmployeeSearch("asha", 1L, 2L, 3L, StatusFilter.ACTIVE_ONLY)
                .withStatusFilter(StatusFilter.INACTIVE_ONLY);

        assertThat(search.nameQuery()).isEqualTo("asha");
        assertThat(search.departmentId()).isEqualTo(1L);
        assertThat(search.designationId()).isEqualTo(2L);
        assertThat(search.gradeId()).isEqualTo(3L);
        assertThat(search.statusFilter()).isEqualTo(StatusFilter.INACTIVE_ONLY);
    }
}
