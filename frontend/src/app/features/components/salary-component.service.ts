import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CreateSalaryComponentRequest, SalaryComponentDefinition } from './salary-component.models';

/**
 * Salary component definitions.
 *
 * Read by HR to populate the structure assignment form, written only by ADMIN (FR-3.4).
 * The split is the API's and is enforced there; this service simply exposes both calls.
 */
@Injectable({ providedIn: 'root' })
export class SalaryComponentService {
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${environment.apiBaseUrl}/salary-components`;
  }

  /**
   * @param includeInactive retired components are excluded by default, because the common
   *     caller is a form building a *new* package and a retired component cannot go into
   *     one. The admin screen asks for them so they can still be seen.
   */
  list(includeInactive = false): Observable<SalaryComponentDefinition[]> {
    const params = includeInactive ? new HttpParams().set('includeInactive', true) : undefined;
    return this.http.get<SalaryComponentDefinition[]>(this.baseUrl, { params });
  }

  create(request: CreateSalaryComponentRequest): Observable<SalaryComponentDefinition> {
    return this.http.post<SalaryComponentDefinition>(this.baseUrl, request);
  }
}
