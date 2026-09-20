import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Payslip } from './payslip.models';

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
}
