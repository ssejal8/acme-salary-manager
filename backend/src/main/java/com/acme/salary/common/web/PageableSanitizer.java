package com.acme.salary.common.web;

import com.acme.salary.common.error.ValidationException;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Turns a client-supplied {@link Pageable} into one the server is willing to execute.
 *
 * <p>Two protections, both required by NFR-1.2:
 *
 * <ul>
 *   <li><b>Page size is capped.</b> {@code ?size=100000} would otherwise ask PostgreSQL
 *       for the whole table and hold it in memory.
 *   <li><b>Sort properties are whitelisted.</b> A {@code sort} parameter goes straight
 *       into a query as a property path, so an unknown value is either a 500 from Spring
 *       Data or an accidental probe of the mapping. An unrecognised key is a 400 naming
 *       what was allowed.
 * </ul>
 *
 * <p>The whitelist also decouples the API from the schema: clients sort by
 * {@code department}, which maps to the {@code department.name} path here, so renaming a
 * field does not break a published contract.
 */
public final class PageableSanitizer {

    public static final int MAX_PAGE_SIZE = 100;

    private PageableSanitizer() {
    }

    /**
     * @param requested what the client asked for
     * @param sortableProperties client sort key to entity property path
     * @param defaultSort applied when the client asked for no ordering; a stable order
     *     matters because pagination without one can repeat or skip rows between pages
     * @throws ValidationException if a requested sort key is not whitelisted
     */
    public static Pageable sanitize(
            Pageable requested, Map<String, String> sortableProperties, Sort defaultSort) {
        Sort sort = translate(requested.getSort(), sortableProperties);
        Sort effectiveSort = sort.isSorted() ? sort : defaultSort;
        if (!requested.isPaged()) {
            // An unpaged request asks for the whole table, which NFR-1.2 forbids. It also
            // cannot be queried for its size — Pageable.unpaged() throws — so it becomes
            // the first page at the cap rather than propagating.
            return PageRequest.of(0, MAX_PAGE_SIZE, effectiveSort);
        }
        int size = Math.min(Math.max(requested.getPageSize(), 1), MAX_PAGE_SIZE);
        return PageRequest.of(requested.getPageNumber(), size, effectiveSort);
    }

    private static Sort translate(Sort requested, Map<String, String> sortableProperties) {
        if (requested.isUnsorted()) {
            return Sort.unsorted();
        }
        Sort translated = Sort.unsorted();
        for (Sort.Order order : requested) {
            String property = sortableProperties.get(order.getProperty());
            if (property == null) {
                throw ValidationException.field("sort",
                        "'%s' is not sortable; allowed values are %s"
                                .formatted(order.getProperty(), sortableProperties.keySet()));
            }
            translated = translated.and(Sort.by(order.getDirection(), property));
        }
        return translated;
    }
}
