import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AssignSalaryStructureRequest,
  SalaryStructure,
  StructureTotals,
} from './salary-structure.models';

/**
 * An employee's compensation packages.
 *
 * Nested under the employee in the API because a structure has no meaning apart from one,
 * and the same nesting is kept here.
 *
 * The `/current` endpoint is deliberately unused: the history response already contains
 * the current revision, flagged as such, so asking for both would mean two requests that
 * could in principle disagree — and `/current` answers **204** when nothing is assigned,
 * which is a second empty-state path to handle for no gain. One request, one source.
 */
@Injectable({ providedIn: 'root' })
export class SalaryStructureService {
  private readonly http = inject(HttpClient);

  private structuresUrl(employeeId: number): string {
    return `${environment.apiBaseUrl}/employees/${employeeId}/salary-structures`;
  }

  /**
   * Every revision of this employee's package, newest first, as the API orders them.
   *
   * An employee with no package assigned yet gets an empty array, not a 404 — which is a
   * coverage gap rather than an error, and is what compensation analytics counts as
   * `employeesWithoutPackage`.
   */
  history(employeeId: number): Observable<SalaryStructure[]> {
    return this.http.get<SalaryStructure[]>(this.structuresUrl(employeeId));
  }

  /**
   * Costs and validates a proposed package without saving anything (FR-4.5).
   *
   * The server rejects here exactly what the assignment would reject, by running the same
   * code path rather than a parallel one — so a preview that succeeds is a genuine promise
   * that the assignment will, and a preview that fails is the real reason it would.
   *
   * That is why the form previews against the server on every edit instead of adding up
   * the figures locally: a browser total would be indicative at best and, under ADR-006's
   * per-component rounding, wrong at worst.
   */
  preview(employeeId: number, request: AssignSalaryStructureRequest): Observable<StructureTotals> {
    return this.http.post<StructureTotals>(`${this.structuresUrl(employeeId)}/preview`, request);
  }

  /**
   * Assigns the package, superseding the current revision (FR-4.1, FR-4.4).
   *
   * Not an update: the previous revision is closed rather than overwritten, so the history
   * stays intact and payroll for an earlier month still resolves to the figures that
   * governed it (ADR-009).
   */
  assign(
    employeeId: number,
    request: AssignSalaryStructureRequest,
  ): Observable<SalaryStructure> {
    return this.http.post<SalaryStructure>(this.structuresUrl(employeeId), request);
  }
}
