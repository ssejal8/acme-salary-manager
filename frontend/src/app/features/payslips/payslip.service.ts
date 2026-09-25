import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../../shared/page-response';
import { Payslip, PayslipQuery, PayslipRow } from './payslip.models';

/**
 * Payslips (FR-6.1, FR-6.2).
 *
 * Both calls are deliberately thin. Which payslips the caller may have is decided entirely
 * by the server — `/me` resolves the employee from the token, and `/{id}` applies a
 * record-level ownership check — so there is nothing for this service to filter and
 * nothing it could usefully assert.
 */
@Injectable({ providedIn: 'root' })
export class PayslipService {
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${environment.apiBaseUrl}/payslips`;
  }

  /**
   * The caller's own published payslips, newest first.
   *
   * No employee id is sent, and that is the point: the endpoint resolves the caller from
   * their token, so there is nothing in the request that could aim it at somebody else.
   */
  mine(): Observable<Payslip[]> {
    return this.http.get<Payslip[]>(`${this.baseUrl}/me`);
  }

  /**
   * One payslip by id.
   *
   * A payslip the caller may not have answers **404**, not 403 — the API refuses to
   * confirm that an id exists. So "not found" here genuinely means "not yours or not
   * there", and the screen should not try to distinguish them either.
   */
  get(id: number): Observable<Payslip> {
    return this.http.get<Payslip>(`${this.baseUrl}/${id}`);
  }

  /**
   * One payslip as a PDF (FR-6.4).
   *
   * Fetched as a blob rather than linked to, because the request needs the bearer token
   * an interceptor adds and a link navigation carries no headers. Authorised identically
   * to {@link get} — it is the same read, rendered differently — so a payslip the caller
   * may not have answers 404 here too.
   */
  downloadPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${id}/pdf`, { responseType: 'blob' });
  }

  /**
   * A paged payslip search (FR-6.5), which is the payroll register when a period is given
   * (FR-7.1).
   *
   * Thin for the same reason as the calls above: what the caller may see is decided by the
   * server from their role and applied in the query, so an EMPLOYEE asking for somebody
   * else's payslips gets their own rather than a refusal. There is nothing for this
   * service to filter.
   */
  search(query: PayslipQuery = {}): Observable<PageResponse<PayslipRow>> {
    return this.http.get<PageResponse<PayslipRow>>(this.baseUrl, {
      params: toSearchParams(query),
    });
  }
}

/**
 * Builds the query string, omitting anything absent.
 *
 * An empty parameter is not the same as no parameter: `?departmentId=` is a bind failure
 * on a `Long`, not "every department".
 */
export function toSearchParams(query: PayslipQuery): HttpParams {
  let params = new HttpParams();
  const numbers: [keyof PayslipQuery, number | undefined][] = [
    ['runId', query.runId],
    ['periodYear', query.periodYear],
    ['periodMonth', query.periodMonth],
    ['departmentId', query.departmentId],
    ['employeeId', query.employeeId],
    ['page', query.page],
    ['size', query.size],
  ];
  for (const [name, value] of numbers) {
    if (value !== undefined) {
      params = params.set(name, value);
    }
  }
  if (query.sort) {
    // The API takes Spring's `property,direction` form.
    params = params.set('sort', `${query.sort},${query.direction ?? 'asc'}`);
  }
  return params;
}
