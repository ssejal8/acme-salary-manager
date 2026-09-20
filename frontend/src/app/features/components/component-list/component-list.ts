import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';
import { ApiFailure } from '../../../core/http/api-error';
import { MoneyPipe, PercentagePipe } from '../../../shared/money.pipe';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { CalculationType, ComponentType } from '../../structures/salary-structure.models';
import {
  CALCULATION_TYPES,
  COMPONENT_TYPES,
  SalaryComponentDefinition,
  calculationTypeLabel,
  componentTypeLabel,
} from '../salary-component.models';
import { SalaryComponentService } from '../salary-component.service';

/**
 * Salary component definitions — the vocabulary every package is built from (FR-3.4).
 *
 * ## Two roles, one screen
 *
 * HR reads these because the assignment form is built from them; only ADMIN may define
 * one, because a component changes what payroll computes for everyone. So the list is
 * shown to both and the form only to ADMIN.
 *
 * That hiding is a courtesy, not the control — `POST /salary-components` is `hasRole(
 * 'ADMIN')` and would refuse an HR caller whatever this screen rendered. What it buys is
 * that HR is not shown a form that could only ever answer 403.
 *
 * ## Retired components
 *
 * A retired component stays valid on the packages that already use it but cannot be added
 * to a new one (ADR-014). They are therefore listed — hiding them would make a historical
 * payslip reference something that appears not to exist — but marked, and the list asks
 * for them explicitly rather than getting them by default.
 */
@Component({
  selector: 'app-component-list',
  imports: [NgTemplateOutlet, ReactiveFormsModule, MoneyPipe, PercentagePipe, EmptyState],
  templateUrl: './component-list.html',
  styleUrl: './component-list.scss',
})
export class ComponentList {
  private readonly components = inject(SalaryComponentService);
  private readonly auth = inject(AuthService);
  private readonly formBuilder = inject(FormBuilder);

  readonly componentTypes = COMPONENT_TYPES;
  readonly calculationTypes = CALCULATION_TYPES;
  readonly calculationTypeLabel = calculationTypeLabel;
  readonly componentTypeLabel = componentTypeLabel;

  /** Only ADMIN may define a component (FR-3.4). */
  readonly canCreate = computed(() => this.auth.hasAnyRole('ADMIN'));

  readonly definitions = signal<SalaryComponentDefinition[]>([]);
  readonly includeInactive = signal(false);
  readonly loaded = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly creating = signal(false);
  readonly createFailure = signal<ApiFailure | null>(null);
  readonly createdCode = signal<string | null>(null);
  readonly formOpen = signal(false);

  readonly earnings = computed(() =>
    this.definitions().filter((definition) => definition.type === 'EARNING'),
  );
  readonly deductions = computed(() =>
    this.definitions().filter((definition) => definition.type === 'DEDUCTION'),
  );

  readonly form = this.formBuilder.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(30)]],
    name: ['', [Validators.required, Validators.maxLength(120)]],
    type: ['EARNING' as ComponentType, [Validators.required]],
    calculationType: ['FLAT' as CalculationType, [Validators.required]],
    value: ['0.00', [Validators.required]],
    taxable: [true],
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.errorMessage.set(null);
    this.components.list(this.includeInactive()).subscribe({
      next: (definitions) => {
        this.definitions.set(definitions);
        this.loaded.set(true);
      },
      error: (failure: unknown) => {
        this.errorMessage.set(
          failure instanceof ApiFailure
            ? failure.message
            : 'The component definitions could not be loaded.',
        );
        this.loaded.set(true);
      },
    });
  }

  toggleInactive(include: boolean): void {
    this.includeInactive.set(include);
    this.load();
  }

  toggleForm(): void {
    this.formOpen.update((open) => !open);
    this.createdCode.set(null);
    this.createFailure.set(null);
  }

  /**
   * The unit the value is expressed in, which depends on the calculation type.
   *
   * Derived from `valueChanges` rather than read straight off the control inside a
   * `computed`. A `FormControl`'s value is not a signal, so a computed over it would be
   * calculated once and never recompute — the label would be stuck on whatever the form
   * started with, which is exactly the ambiguity it exists to remove.
   */
  private readonly calculationType = toSignal(
    this.form.controls.calculationType.valueChanges,
    { initialValue: this.form.controls.calculationType.value },
  );

  readonly valueUnit = computed(() =>
    this.calculationType() === 'PERCENT_OF_BASIC' ? '% of basic' : 'per month',
  );

  isPercentage(definition: SalaryComponentDefinition): boolean {
    return definition.calculationType === 'PERCENT_OF_BASIC';
  }

  fieldError(field: 'code' | 'name' | 'value'): string | null {
    // The server's message wins where there is one: it knows about code uniqueness and
    // the 100% ceiling, neither of which this form can check.
    const fromServer = this.createFailure()?.messageFor(field);
    if (fromServer) {
      return fromServer;
    }
    const control = this.form.controls[field];
    if (control.valid || !(control.touched || control.dirty)) {
      return null;
    }
    return control.hasError('required') ? 'This is required' : 'Check this value';
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.creating()) {
      return;
    }

    this.creating.set(true);
    this.createFailure.set(null);
    this.createdCode.set(null);

    const request = this.form.getRawValue();
    this.components.create(request).subscribe({
      next: (created) => {
        this.creating.set(false);
        this.createdCode.set(created.code);
        this.form.reset({
          code: '',
          name: '',
          type: 'EARNING',
          calculationType: 'FLAT',
          value: '0.00',
          taxable: true,
        });
        // Refetched rather than appended locally: the server normalises the code to
        // uppercase and fills in defaults, so the row it returns is the truth.
        this.load();
      },
      error: (failure: unknown) => {
        this.creating.set(false);
        this.createFailure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }
}
