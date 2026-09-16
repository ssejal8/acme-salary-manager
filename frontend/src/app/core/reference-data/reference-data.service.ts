import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Department, Designation, Grade, ReferenceData } from './reference-data.models';

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

  /** All three at once. Fails as a whole if any one of them fails. */
  all(): Observable<ReferenceData> {
    return forkJoin({
      departments: this.departments(),
      designations: this.designations(),
      grades: this.grades(),
    });
  }
}
