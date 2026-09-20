import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CompensationOverview } from './compensation.models';

/**
 * Compensation analytics.
 *
 * Only the combined overview is used. The API also offers `/summary`, `/by-department` and
 * `/by-grade` separately, for a caller that wants one tile — but this screen wants all
 * three, and one request cannot show a department breakdown computed a second later than
 * the organisation total it is a share of.
 */
@Injectable({ providedIn: 'root' })
export class CompensationService {
  private readonly http = inject(HttpClient);

  overview(): Observable<CompensationOverview> {
    return this.http.get<CompensationOverview>(`${environment.apiBaseUrl}/reports/compensation`);
  }
}
