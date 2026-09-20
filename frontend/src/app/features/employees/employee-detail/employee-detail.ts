import { Component, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { IsoDatePipe } from '../../../shared/dates';
import { SalaryHistory } from '../../structures/salary-history/salary-history';
import { EmployeeSummary } from '../employee.models';
import { EmployeeService } from '../employee.service';

/**
 * One employee's record.
 *
 * Read-only, and that is the API's current surface rather than a choice made here: the
 * write endpoints (create, update, deactivate) are not built yet, so there is nothing to
 * put behind an Edit button. When they land, this is where the form goes.
 *
 * Compensation is rendered by {@link SalaryHistory}, which owns its own request, loading
 * and failure states. Keeping it separate means a failure to read the salary history —
 * a different endpoint with stricter reasons to fail — still leaves the employee's record
 * on screen.
 */
@Component({
  selector: 'app-employee-detail',
  imports: [RouterLink, IsoDatePipe, SalaryHistory],
  templateUrl: './employee-detail.html',
  styleUrl: './employee-detail.scss',
})
export class EmployeeDetail {
  private readonly employees = inject(EmployeeService);

  /**
   * Bound from the route by `withComponentInputBinding`, and typed as a string because a
   * URL segment is one. Parsed below rather than trusted: `/employees/abc` must produce a
   * clean message, not `NaN` in a request path.
   */
  readonly id = input.required<string>();

  readonly errorMessage = signal<string | null>(null);
  readonly loaded = signal(false);

  readonly employee = toSignal(
    toObservable(this.id).pipe(
      tap(() => {
        this.errorMessage.set(null);
        this.loaded.set(false);
      }),
      switchMap((rawId) => {
        const employeeId = Number(rawId);
        if (!Number.isInteger(employeeId) || employeeId <= 0) {
          this.errorMessage.set('That is not a valid employee reference.');
          this.loaded.set(true);
          return of(null);
        }
        return this.employees.get(employeeId).pipe(
          tap(() => this.loaded.set(true)),
          catchError((failure: unknown) => {
            this.errorMessage.set(
              failure instanceof ApiFailure
                ? failure.message
                : 'This employee could not be loaded.',
            );
            this.loaded.set(true);
            return of(null);
          }),
        );
      }),
    ),
    { initialValue: null as EmployeeSummary | null },
  );
}
