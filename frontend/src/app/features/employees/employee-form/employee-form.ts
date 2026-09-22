import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of, switchMap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { ReferenceData } from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { IsoDatePipe } from '../../../shared/dates';
import {
  CreateEmployeeRequest,
  EmployeeSummary,
  UpdateEmployeeRequest,
} from '../employee.models';
import { EmployeeService } from '../employee.service';

/** The fields the form can edit, by name, for the error lookup below. */
type FieldName =
  | 'employeeCode'
  | 'firstName'
  | 'lastName'
  | 'workEmail'
  | 'dateOfJoining'
  | 'departmentId'
  | 'designationId'
  | 'gradeId';

/**
 * Create or edit an employee (FR-2.1, FR-2.3).
 *
 * ## One component for both
 *
 * The two screens differ by two fields and a verb, so splitting them would mean
 * maintaining the same six controls, the same reference-data load and the same error
 * handling twice. Which mode it is in is derived from the route: `/employees/new` has no
 * id, `/employees/:id/edit` does.
 *
 * ## What editing cannot change
 *
 * The employee code and the date of joining are immutable after creation (FR-2.3), and in
 * edit mode they are shown as read-only facts rather than disabled inputs. A greyed-out
 * box invites the question "why can't I?", where a plain value with a sentence beneath it
 * answers it: the code appears on published payslips, and the joining date is what every
 * salary revision is validated against.
 *
 * ## Validation is mirrored, not owned
 *
 * The controls carry the same `required` and length rules as the API so the obvious
 * mistakes do not need a round trip, but the server decides. Uniqueness of the code and
 * the email cannot be checked here at all — that needs the database — so those arrive as
 * a 409 carrying a field-level message (FR-2.2), and the message is shown against the
 * control it names.
 */
@Component({
  selector: 'app-employee-form',
  imports: [ReactiveFormsModule, RouterLink, IsoDatePipe],
  templateUrl: './employee-form.html',
  styleUrl: './employee-form.scss',
})
export class EmployeeForm {
  private readonly employees = inject(EmployeeService);
  private readonly referenceData = inject(ReferenceDataService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);

  /**
   * Bound from the route, and absent on `/employees/new`. A string, because a URL segment
   * is one.
   */
  readonly id = input<string>();

  readonly references = signal<ReferenceData | null>(null);

  /** The record being edited, or null when creating. */
  readonly existing = signal<EmployeeSummary | null>(null);

  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly saving = signal(false);
  readonly saveFailure = signal<ApiFailure | null>(null);

  readonly editing = computed(() => this.existing() !== null);

  readonly form = this.formBuilder.nonNullable.group({
    // Lengths mirror the columns and the API's Bean Validation, so a value that would be
    // truncated is refused before it is sent.
    employeeCode: ['', [Validators.required, Validators.maxLength(20)]],
    firstName: ['', [Validators.required, Validators.maxLength(80)]],
    lastName: ['', [Validators.required, Validators.maxLength(80)]],
    workEmail: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    dateOfJoining: ['', [Validators.required]],
    departmentId: [null as number | null, [Validators.required]],
    designationId: [null as number | null, [Validators.required]],
    gradeId: [null as number | null, [Validators.required]],
  });

  constructor() {
    toObservable(this.id)
      .pipe(
        switchMap((rawId) => {
          this.loading.set(true);
          this.loadError.set(null);

          if (rawId === undefined) {
            // Creating: only the three reference lists are needed.
            return forkJoin({ references: this.referenceData.all(), employee: of(null) });
          }

          const employeeId = Number(rawId);
          if (!Number.isInteger(employeeId) || employeeId <= 0) {
            this.loadError.set('That is not a valid employee reference.');
            this.loading.set(false);
            return of(null);
          }
          // In parallel, so the form waits for the slower of the two rather than both.
          return forkJoin({
            references: this.referenceData.all(),
            employee: this.employees.get(employeeId),
          });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (loaded) => {
          if (!loaded) {
            return;
          }
          this.references.set(loaded.references);
          this.existing.set(loaded.employee);
          this.seedForm(loaded.employee);
          this.loading.set(false);
        },
        error: (failure: unknown) => {
          this.loadError.set(
            failure instanceof ApiFailure ? failure.message : 'This form could not be loaded.',
          );
          this.loading.set(false);
        },
      });
  }

  /**
   * Fills the form from the record being edited, or clears it for a new one.
   *
   * Nothing is defaulted when creating — not the department, not today's date. Every one
   * of these is a fact about a person that somebody has to supply, and a pre-filled
   * default is the kind of thing that gets saved unread.
   */
  private seedForm(employee: EmployeeSummary | null): void {
    if (!employee) {
      return;
    }
    this.form.patchValue({
      employeeCode: employee.employeeCode,
      firstName: employee.firstName,
      lastName: employee.lastName,
      workEmail: employee.workEmail,
      dateOfJoining: employee.dateOfJoining,
      departmentId: employee.department.id,
      designationId: employee.designation.id,
      gradeId: employee.grade.id,
    });
    // Immutable in edit mode, and disabled rather than merely ignored so the form model
    // and the screen agree about what can be submitted (FR-2.3).
    this.form.controls.employeeCode.disable();
    this.form.controls.dateOfJoining.disable();
  }

  /**
   * Trims a text field when it loses focus.
   *
   * Not cosmetic. A pasted address arrives as `" asha@acme.test "`, which the email
   * validator rejects — so without this the form would refuse a value it was about to
   * trim and send anyway, and tell the user their address is not an address.
   */
  trim(field: 'employeeCode' | 'firstName' | 'lastName' | 'workEmail'): void {
    const control = this.form.controls[field];
    const trimmed = control.value.trim();
    if (trimmed !== control.value) {
      control.setValue(trimmed);
    }
  }

  /**
   * The message for one control: the server's if it sent one, otherwise ours.
   *
   * The server's wins because it knows things this form cannot — that a code is already
   * in use, that a grade id no longer exists — and its wording is more specific than any
   * generic message here.
   */
  fieldError(field: FieldName): string | null {
    const fromServer = this.saveFailure()?.messageFor(field);
    if (fromServer) {
      return fromServer;
    }
    const control = this.form.controls[field];
    if (control.valid || !(control.touched || control.dirty)) {
      return null;
    }
    if (control.hasError('required')) {
      return 'This is required';
    }
    if (control.hasError('email')) {
      return 'This does not look like an email address';
    }
    if (control.hasError('maxlength')) {
      return 'This is too long';
    }
    return 'Check this value';
  }

  /** An envelope-level failure — one that names no field, so it belongs above the form. */
  readonly generalError = computed(() => {
    const failure = this.saveFailure();
    return failure && !failure.hasFieldErrors ? failure.message : null;
  });

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.saveFailure.set(null);

    const existing = this.existing();
    const request = this.form.getRawValue();

    const saved = existing
      ? this.employees.update(existing.id, {
          firstName: request.firstName.trim(),
          lastName: request.lastName.trim(),
          workEmail: request.workEmail.trim(),
          departmentId: request.departmentId as number,
          designationId: request.designationId as number,
          gradeId: request.gradeId as number,
        } satisfies UpdateEmployeeRequest)
      : this.employees.create({
          employeeCode: request.employeeCode.trim(),
          firstName: request.firstName.trim(),
          lastName: request.lastName.trim(),
          workEmail: request.workEmail.trim(),
          dateOfJoining: request.dateOfJoining,
          departmentId: request.departmentId as number,
          designationId: request.designationId as number,
          gradeId: request.gradeId as number,
        } satisfies CreateEmployeeRequest);

    saved.subscribe({
      next: (employee) => {
        // To the record, which is where the result of either verb is visible — and it
        // shows what the server stored rather than what was typed.
        void this.router.navigate(['/employees', employee.id]);
      },
      error: (failure: unknown) => {
        this.saving.set(false);
        this.saveFailure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }

  /** Where Cancel goes: back to the record when editing, to the list when creating. */
  readonly cancelTarget = computed(() => {
    const existing = this.existing();
    return existing ? ['/employees', existing.id] : ['/employees'];
  });
}
