/** Organisation reference data, mirroring the `orgdata` DTOs. */

export interface Department {
  id: number;
  code: string;
  name: string;
}

export interface Designation {
  id: number;
  title: string;
}

export interface Grade {
  id: number;
  name: string;
  /**
   * CTC band bounds, as decimal strings. Either is absent when that side is unbounded,
   * which never rejects a package (FR-4.3) — so "missing" means "no limit", not zero.
   */
  minCtc?: string;
  maxCtc?: string;
}

/** The three lists together, which is how every screen that needs one needs them. */
export interface ReferenceData {
  departments: Department[];
  designations: Designation[];
  grades: Grade[];
}

export const EMPTY_REFERENCE_DATA: ReferenceData = {
  departments: [],
  designations: [],
  grades: [],
};

/** Request body for adding or retitling a designation (FR-3.2). */
export interface SaveDesignationRequest {
  title: string;
}

/**
 * Request body for adding or amending a grade (FR-3.3).
 *
 * Either bound may be null, and that is meaningful: an absent bound is unbounded on that
 * side, so a top grade with no ceiling never rejects a package (FR-4.3). Amounts are
 * strings, like all money in this API (ADR-006).
 */
export interface SaveGradeRequest {
  name: string;
  minCtc: string | null;
  maxCtc: string | null;
}
