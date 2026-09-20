import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PayrollRunDetail, StartPayrollRunRequest } from './payroll-run.models';

/**
 * Payroll runs (FR-5.1).
 *
 * Only `start` is here. The API also lists runs, reads one, recomputes, finalises and
 * cancels, and those calls are deliberately absent rather than added speculatively: each
 * belongs with the screen that needs it, and a review screen will want to say far more
 * about a draft than a method stub written now could guess.
 */
@Injectable({ providedIn: 'root' })
export class PayrollRunService {
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${environment.apiBaseUrl}/payroll-runs`;
  }

  /**
   * Starts a draft run for a period and returns it with every payslip computed
   * (FR-5.1, FR-5.8).
   *
   * Nothing is published: the payslips exist so there is something to review, and the
   * employees concerned cannot see them until the run is finalised.
   *
   * This is the one request in the application that is slow by design — the whole run is a
   * single transaction over the entire payroll (FR-5.9), and NFR-1.3 budgets 60 seconds
   * for a thousand employees. The caller is expected to say so on screen.
   */
  start(request: StartPayrollRunRequest): Observable<PayrollRunDetail> {
    return this.http.post<PayrollRunDetail>(this.baseUrl, request);
  }
}
