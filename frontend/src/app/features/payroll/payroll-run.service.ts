import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../shared/page-response';
import { PayslipRow } from '../payslips/payslip.models';
import {
  PayrollRunDetail,
  PayrollRunSummary,
  RecomputePayrollRunRequest,
  StartPayrollRunRequest,
} from './payroll-run.models';

/**
 * Payroll runs — the whole cycle (FR-5.1 to FR-5.10).
 *
 * The four state changes are `POST`s to named sub-resources rather than a `PATCH` of a
 * status field, mirroring the API. Finalising is not "setting status to FINALISED": it
 * publishes payslips to every employee on the run and makes it immutable, and a verb
 * makes that legible where a field assignment would not.
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

  /**
   * Runs newest period first, with their totals and no payslips (FR-5.10).
   *
   * Paged in the database like every other list (NFR-1.2). The payslips are deliberately
   * absent from this payload — a year of runs would otherwise carry a hundred thousand
   * payslips into a list screen.
   */
  list(page = 0, size = 20): Observable<PageResponse<PayrollRunSummary>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<PayrollRunSummary>>(this.baseUrl, { params });
  }

  /**
   * One run with every payslip in it (FR-5.6).
   *
   * The review screen reads this for the run's totals and — the part that matters — the
   * complete set of loss-of-pay days across the whole run. It cannot use a page for that:
   * the recompute endpoint treats its adjustment list as the whole picture, so sending
   * only what one page holds would clear everybody else's unpaid days.
   */
  get(id: number): Observable<PayrollRunDetail> {
    return this.http.get<PayrollRunDetail>(`${this.baseUrl}/${id}`);
  }

  /**
   * The payslips in one run, a page at a time, each row carrying its employee's name.
   *
   * What the review table renders. The rows come from here rather than from {@link get}
   * for two reasons: ten thousand rows do not belong in the DOM, and a payslip inside a
   * run identifies its employee by id alone — this endpoint resolves the name.
   */
  payslips(id: number, page = 0, size = 25): Observable<PageResponse<PayslipRow>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<PayslipRow>>(`${this.baseUrl}/${id}/payslips`, { params });
  }

  /**
   * Recomputes a draft, applying loss-of-pay days (FR-5.4, FR-5.6).
   *
   * Only a draft can be recomputed; a finalised run is immutable and answers 409.
   */
  recompute(
    id: number,
    request: RecomputePayrollRunRequest,
  ): Observable<PayrollRunDetail> {
    return this.http.post<PayrollRunDetail>(`${this.baseUrl}/${id}/recompute`, request);
  }

  /**
   * Publishes the payslips already computed (FR-5.8).
   *
   * Nothing is recomputed, which is the point: the figures that were reviewed are the
   * figures the employees see. Irreversible — a correction means cancelling and running a
   * fresh draft (FR-6.6).
   */
  finalise(id: number): Observable<PayrollRunDetail> {
    return this.http.post<PayrollRunDetail>(`${this.baseUrl}/${id}/finalise`, {});
  }

  /**
   * Abandons a draft and frees its period to be run again (FR-5.7).
   *
   * Its payslips stay on record as what was computed, unpublished.
   */
  cancel(id: number): Observable<PayrollRunDetail> {
    return this.http.post<PayrollRunDetail>(`${this.baseUrl}/${id}/cancel`, {});
  }
}
