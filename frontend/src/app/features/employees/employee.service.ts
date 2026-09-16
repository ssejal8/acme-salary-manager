import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../shared/page-response';
import { EmployeeQuery, EmployeeSummary } from './employee.models';

/**
 * Reads the employee list and individual records.
 *
 * Paging, filtering and sorting are all the server's job (NFR-1.2) — this builds the query
 * string and returns what comes back. Nothing is filtered or sorted in the browser, so
 * what a user sees on page 3 is a real page 3 and the total is a real count.
 */
@Injectable({ providedIn: 'root' })
export class EmployeeService {
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${environment.apiBaseUrl}/employees`;
  }

  list(query: EmployeeQuery): Observable<PageResponse<EmployeeSummary>> {
    return this.http.get<PageResponse<EmployeeSummary>>(this.baseUrl, {
      params: toParams(query),
    });
  }

  get(id: number): Observable<EmployeeSummary> {
    return this.http.get<EmployeeSummary>(`${this.baseUrl}/${id}`);
  }
}

/**
 * Builds the query string.
 *
 * Empty filters are omitted rather than sent blank: `?departmentId=` is a bind failure on
 * a `Long` parameter, and `?q=` would be a search for nothing rather than no search at
 * all. `status` is always sent, because leaving it off silently means `ACTIVE_ONLY` and
 * being explicit costs nothing.
 */
export function toParams(query: EmployeeQuery): HttpParams {
  let params = new HttpParams()
    .set('status', query.status)
    .set('page', query.page)
    .set('size', query.size)
    // The API takes Spring's `property,direction` form.
    .set('sort', `${query.sort},${query.direction}`);

  const name = query.q?.trim();
  if (name) {
    params = params.set('q', name);
  }
  if (query.departmentId !== undefined) {
    params = params.set('departmentId', query.departmentId);
  }
  if (query.designationId !== undefined) {
    params = params.set('designationId', query.designationId);
  }
  if (query.gradeId !== undefined) {
    params = params.set('gradeId', query.gradeId);
  }
  return params;
}
