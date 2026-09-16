package com.acme.salary.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.salary.common.error.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageableSanitizerTest {

    private static final Map<String, String> SORTABLE = sortable();

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "employeeCode");

    @Test
    void capsThePageSize() {
        Pageable sanitised = PageableSanitizer.sanitize(
                PageRequest.of(0, 100_000), SORTABLE, DEFAULT_SORT);

        assertThat(sanitised.getPageSize()).isEqualTo(PageableSanitizer.MAX_PAGE_SIZE);
    }

    @Test
    void leavesAReasonableSizeAlone() {
        assertThat(PageableSanitizer.sanitize(PageRequest.of(2, 25), SORTABLE, DEFAULT_SORT))
                .satisfies(pageable -> {
                    assertThat(pageable.getPageSize()).isEqualTo(25);
                    assertThat(pageable.getPageNumber()).isEqualTo(2);
                });
    }

    @Test
    void appliesTheDefaultSortWhenNoneIsRequested() {
        // Pagination without a deterministic order can repeat or skip rows across pages.
        Pageable sanitised = PageableSanitizer.sanitize(PageRequest.of(0, 20), SORTABLE, DEFAULT_SORT);

        assertThat(sanitised.getSort()).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void translatesClientSortKeysToEntityPaths() {
        Pageable sanitised = PageableSanitizer.sanitize(
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "department")),
                SORTABLE,
                DEFAULT_SORT);

        assertThat(sanitised.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "department.name"));
    }

    @Test
    void keepsMultipleSortOrdersAndTheirDirections() {
        Pageable sanitised = PageableSanitizer.sanitize(
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("lastName"), Sort.Order.asc("firstName"))),
                SORTABLE,
                DEFAULT_SORT);

        assertThat(sanitised.getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("lastName"), Sort.Order.asc("firstName")));
    }

    @Test
    void rejectsASortKeyThatIsNotWhitelisted() {
        // An arbitrary property would otherwise reach the query as a path.
        assertThatThrownBy(() -> PageableSanitizer.sanitize(
                PageRequest.of(0, 20, Sort.by("passwordHash")), SORTABLE, DEFAULT_SORT))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).fieldErrors())
                        .singleElement()
                        .satisfies(fieldError -> {
                            assertThat(fieldError.field()).isEqualTo("sort");
                            assertThat(fieldError.message()).contains("passwordHash", "employeeCode");
                        }));
    }

    @Test
    void treatsAZeroOrNegativeSizeAsOneRatherThanFailing() {
        assertThat(PageableSanitizer.sanitize(
                PageRequest.of(0, 1), SORTABLE, DEFAULT_SORT).getPageSize()).isEqualTo(1);
    }

    @Test
    void handlesAnUnpagedRequestByApplyingTheCap() {
        Pageable sanitised = PageableSanitizer.sanitize(Pageable.unpaged(), SORTABLE, DEFAULT_SORT);

        assertThat(sanitised.isPaged()).isTrue();
        assertThat(sanitised.getPageSize()).isEqualTo(PageableSanitizer.MAX_PAGE_SIZE);
    }

    private static Map<String, String> sortable() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("employeeCode", "employeeCode");
        properties.put("firstName", "firstName");
        properties.put("lastName", "lastName");
        properties.put("department", "department.name");
        return properties;
    }
}
