/**
 * The paging contract every list endpoint returns.
 *
 * Mirrors `com.acme.salary.common.web.PageResponse`, which exists precisely so clients do
 * not depend on Spring's own `Page` serialisation.
 */
export interface PageResponse<T> {
  content: T[];
  /** Zero-based. */
  page: number;
  /** The size actually applied, after the server's cap of 100. */
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/** A reference-data entry as it appears nested inside another resource. */
export interface Reference {
  id: number;
  label: string;
}

/** An empty page, for seeding component state before the first response arrives. */
export function emptyPage<T>(size = 20): PageResponse<T> {
  return {
    content: [],
    page: 0,
    size,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false,
  };
}

/**
 * The 1-based row range shown, e.g. `{ from: 21, to: 40 }` on the second page of 20.
 *
 * Derived from the response rather than from the requested page and size, because the
 * server may have capped the size, and the last page is usually short.
 */
export function shownRange(page: PageResponse<unknown>): { from: number; to: number } {
  if (page.totalElements === 0) {
    return { from: 0, to: 0 };
  }
  const from = page.page * page.size + 1;
  return { from, to: from + page.content.length - 1 };
}
