import { Component, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { IsoDatePipe } from '../../../shared/dates';
import { EmployeeSummary } from '../employee.models';
import { EmployeeService } from '../employee.service';

/**
 * One employee's record.
 *
 * Read-only, and that is the API's current surface rather than a choice made here: the
 * write endpoints (create, update, deactivate) are not built yet, so there is nothing to
 * put behind an Edit button. When they land, this is where the form goes.
 *
 * There is no compensation on this screen either, for the same reason in reverse — the
 * structure endpoints exist, but the history and assignment screens are a separate
 * feature area (architecture §6.1) and not part of this pass.
 */
@Component({
  selector: 'app-employee-detail',
  imports: [RouterLink, IsoDatePipe],
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
