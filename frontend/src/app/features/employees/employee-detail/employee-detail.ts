import { Component, computed, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { IsoDatePipe } from '../../../shared/dates';
import { SalaryHistory } from '../../structures/salary-history/salary-history';
import { EmployeeSummary } from '../employee.models';
import { EmployeeService } from '../employee.service';

/**
 * One employee's record, with the two actions that change it.
 *
 * Editing is a separate screen — a form beside a record it is editing reads as two
 * versions of the same person — so this screen links to it. Recording an exit stays here,
 * because it is one date and a decision, and because the thing being confirmed is the
 * record on screen (FR-2.5).
 *
 * Compensation is rendered by {@link SalaryHistory}, which owns its own request, loading
 * and failure states. Keeping it separate means a failure to read the salary history —
 * a different endpoint with stricter reasons to fail — still leaves the employee's record
 * on screen.
 */
@Component({
  selector: 'app-employee-detail',
  imports: [RouterLink, ReactiveFormsModule, IsoDatePipe, SalaryHistory],
  templateUrl: './employee-detail.html',
  styleUrl: './employee-detail.scss',
})
export class EmployeeDetail {
  private readonly employees = inject(EmployeeService);
  private readonly formBuilder = inject(FormBuilder);

  /**
   * Bound from the route by `withComponentInputBinding`, and typed as a string because a
   * URL segment is one. Parsed below rather than trusted: `/employees/abc` must produce a
   * clean message, not `NaN` in a request path.
   */
  readonly id = input.required<string>();

  readonly errorMessage = signal<string | null>(null);
  readonly loaded = signal(false);

  /**
   * The record as it stands after a deactivation, if one happened on this screen.
   *
   * The fetched record is a signal derived from the route and cannot be written to, so
   * the result of the exit is held beside it and preferred by {@link currentRecord}. The
   * alternative — refetching — would be a second request for a response the deactivate
   * call already returned in full.
   */
  private readonly deactivated = signal<EmployeeSummary | null>(null);

  /**
   * Declared above `employee` on purpose. `toSignal` subscribes during field
   * initialisation, so the reset inside its `tap` runs while the class is still being
   * built — anything it touches has to exist by then.
   */
  readonly exitFormOpen = signal(false);
  readonly deactivating = signal(false);
  readonly deactivateFailure = signal<ApiFailure | null>(null);

  readonly employee = toSignal(
    toObservable(this.id).pipe(
      tap(() => {
        this.errorMessage.set(null);
        this.loaded.set(false);
        // A different employee is on screen now, so last one's outcome is not theirs.
        this.deactivated.set(null);
        this.exitFormOpen.set(false);
        this.deactivateFailure.set(null);
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

  /** What the screen renders: the record, with a deactivation applied if one happened. */
  readonly currentRecord = computed(() => this.deactivated() ?? this.employee());

  /**
   * The exit date, deliberately not defaulted to today.
   *
   * Payroll eligibility for a period is derived from it (FR-2.6), so a default would
   * quietly decide whether this person is paid for the month they left.
   */
  readonly exitForm = this.formBuilder.nonNullable.group({
    exitDate: ['', [Validators.required]],
  });

  /** Whether the exit has been submitted, so a blank date is only called out once asked. */
  private readonly exitAttempted = signal(false);

  /**
   * The chosen date, bridged into the signal graph.
   *
   * Not read off the control inside the computed below, and that is the whole point: a
   * `FormControl`'s value and touched state are not signals, so a computed over them is
   * evaluated once and never recomputes — the message would stay on whatever it said when
   * the panel first rendered. This project has hit that bug before; `toSignal` over
   * `valueChanges` is the fix.
   */
  private readonly exitDateValue = toSignal(this.exitForm.controls.exitDate.valueChanges, {
    initialValue: '',
  });

  readonly exitDateError = computed(() => {
    const fromServer = this.deactivateFailure()?.messageFor('exitDate');
    if (fromServer) {
      return fromServer;
    }
    return this.exitAttempted() && !this.exitDateValue().trim() ? 'This is required' : null;
  });

  /** A refusal that names no field — an employee who has already left, for instance. */
  readonly deactivateError = computed(() => {
    const failure = this.deactivateFailure();
    return failure && !failure.hasFieldErrors ? failure.message : null;
  });

  toggleExitForm(): void {
    this.exitFormOpen.update((open) => !open);
    this.deactivateFailure.set(null);
    this.exitAttempted.set(false);
    this.exitForm.reset({ exitDate: '' });
  }

  deactivate(): void {
    const record = this.currentRecord();
    this.exitAttempted.set(true);
    if (!record || this.exitForm.invalid || this.deactivating()) {
      return;
    }

    this.deactivating.set(true);
    this.deactivateFailure.set(null);

    this.employees
      .deactivate(record.id, { exitDate: this.exitForm.getRawValue().exitDate })
      .subscribe({
        next: (updated) => {
          // The response carries the stored record, so the screen can show the exit
          // without asking for it again.
          this.deactivated.set(updated);
          this.deactivating.set(false);
          this.exitFormOpen.set(false);
        },
        error: (failure: unknown) => {
          this.deactivating.set(false);
          this.deactivateFailure.set(failure instanceof ApiFailure ? failure : null);
        },
      });
  }
}
