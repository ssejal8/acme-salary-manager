import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Department,
  Designation,
  Grade,
  ReferenceData,
  SaveDesignationRequest,
  SaveGradeRequest,
} from './reference-data.models';

/**
 * Departments, designations and grades — the vocabulary the employee screens filter and
 * display by.
 *
 * Not cached, deliberately. ADR-019 rules out a client-side store, and the hazard it is
 * avoiding is showing stale data; the cost is a refetch when a screen is revisited, which
 * for three short lists on an internal tool is not worth a cache-invalidation bug.
 *
 * The three are fetched in parallel rather than in sequence, so a filter bar waits for the
 * slowest of them rather than the sum of all three.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceDataService {
  private readonly http = inject(HttpClient);

  departments(): Observable<Department[]> {
    return this.http.get<Department[]>(`${environment.apiBaseUrl}/departments`);
  }

  designations(): Observable<Designation[]> {
    return this.http.get<Designation[]>(`${environment.apiBaseUrl}/designations`);
  }

  grades(): Observable<Grade[]> {
    return this.http.get<Grade[]>(`${environment.apiBaseUrl}/grades`);
  }

  /**
   * Adds a department (FR-3.1). ADMIN only, enforced by the API.
   *
   * The code is sent only on creation: it is immutable afterwards, because reports and
   * saved filters refer to it.
   */
  createDepartment(request: { code: string; name: string }): Observable<Department> {
    return this.http.post<Department>(`${environment.apiBaseUrl}/departments`, request);
  }

  /** Renames a department. The name is the only editable field. */
  renameDepartment(id: number, name: string): Observable<Department> {
    return this.http.put<Department>(`${environment.apiBaseUrl}/departments/${id}`, { name });
  }

  createDesignation(request: SaveDesignationRequest): Observable<Designation> {
    return this.http.post<Designation>(`${environment.apiBaseUrl}/designations`, request);
  }

  retitleDesignation(id: number, request: SaveDesignationRequest): Observable<Designation> {
    return this.http.put<Designation>(`${environment.apiBaseUrl}/designations/${id}`, request);
  }

  createGrade(request: SaveGradeRequest): Observable<Grade> {
    return this.http.post<Grade>(`${environment.apiBaseUrl}/grades`, request);
  }

  /**
   * Amends a grade's name or CTC band (FR-3.3).
   *
   * A band change applies to the next assignment and does not re-validate the packages
   * already assigned against it — a salary structure records what was agreed (ADR-009).
   */
  updateGrade(id: number, request: SaveGradeRequest): Observable<Grade> {
    return this.http.put<Grade>(`${environment.apiBaseUrl}/grades/${id}`, request);
  }

  /** All three at once. Fails as a whole if any one of them fails. */
  all(): Observable<ReferenceData> {
    return forkJoin({
      departments: this.departments(),
      designations: this.designations(),
      grades: this.grades(),
    });
  }
}
