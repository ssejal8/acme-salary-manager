package com.acme.salary.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The paged-response contract for every list endpoint.
 *
 * <p>Spring's own {@code Page} implementations are deliberately not serialised: their JSON
 * shape is not a stable contract (Spring Boot warns about exactly this), and it leaks
 * paging internals a client should not depend on. This record is the published shape
 * instead.
 *
 * @param content the rows on this page, already mapped to response DTOs
 * @param page zero-based page number
 * @param size page size actually applied, after the server's cap
 * @param totalElements total matching rows, counted in the database
 * @param totalPages total pages at this size
 * @param hasNext whether a further page exists
 * @param hasPrevious whether an earlier page exists
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    /** Maps a repository page into the response shape in one step. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
