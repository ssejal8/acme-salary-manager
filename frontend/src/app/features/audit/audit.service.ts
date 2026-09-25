import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../shared/page-response';
import { AuditEvent, AuditQuery } from './audit.models';

/**
 * The audit trail (FR-8.2).
 *
 * One read, and deliberately no writes: rows are written by the features they describe,
 * inside the transaction of the change itself (ADR-013). A service with a `create` here
 * would be a way to write history that did not happen.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  search(query: AuditQuery = {}): Observable<PageResponse<AuditEvent>> {
    let params = new HttpParams();
    // Absent rather than blank: `?entityId=` is a bind failure on a Long, and `?action=`
    // would be a search for the empty action rather than for any action.
    for (const [name, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(name, String(value));
      }
    }
    return this.http.get<PageResponse<AuditEvent>>(
      `${environment.apiBaseUrl}/audit-events`,
      { params },
    );
  }
}
