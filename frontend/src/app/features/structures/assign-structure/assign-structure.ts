import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  FormArray,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, forkJoin, of, switchMap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { IsoDatePipe } from '../../../shared/dates';
import { MoneyPipe } from '../../../shared/money.pipe';
import {
  BASIC_CODE,
  SalaryComponentDefinition,
} from '../../components/salary-component.models';
import { SalaryComponentService } from '../../components/salary-component.service';
import { EmployeeSummary } from '../../employees/employee.models';
import { EmployeeService } from '../../employees/employee.service';
import {
  AssignSalaryStructureRequest,
  ComponentAssignment,
  SalaryStructure,
  StructureTotals,
} from '../salary-structure.models';
import { SalaryStructureService } from '../salary-structure.service';

/** How long to wait after the last edit before asking the server to cost the package. */
const PREVIEW_DEBOUNCE_MS = 400;

/** One row of the form: a component, whether it is included, and its configured value. */
interface ComponentRow {
  componentId: number;
  included: boolean;
  value: string;
}

/**
 * Assign a compensation package (FR-4.1–4.6).
 *
 * The one genuinely interactive screen in this application, and the one ADR-003 cites as a
 * reason for choosing Angular over server-rendered templates: gross, net and annual CTC
 * update as the figures are edited.
 *
 * ## The server computes; this screen only asks
 *
 * Every figure shown comes from `POST …/preview`, debounced on each edit. Nothing is added
 * up in the browser, and that is not caution for its own sake — components are rounded
 * individually before being summed (ADR-006, NFR-3.3), so a total computed here would
 * disagree with the one that gets saved.
 *
 * The preview endpoint runs the *same* validation as the assignment, so it doubles as
 * validation: whatever it rejects is exactly what saving would reject, with the same
 * message. That is why this screen has almost no business rules of its own. It does not
 * know that a package needs a positive BASIC, that deductions may not exceed gross, or
 * where a grade's CTC band sits — it asks, and shows the answer.
 *
 * ## The grade band asks for itself
 *
 * A package outside the employee's band is allowed, but only deliberately (FR-4.3). The
 * server signals that by rejecting the preview with a field error on `overrideReason`, so
 * the reason box appears exactly when the server says one is needed, carrying the server's
 * own explanation of which band was missed and by how much.
 */
@Component({
  selector: 'app-assign-structure',
  imports: [ReactiveFormsModule, RouterLink, IsoDatePipe, MoneyPipe],
  templateUrl: './assign-structure.html',
  styleUrl: './assign-structure.scss',
})
export class AssignStructure {
  private readonly employees = inject(EmployeeService);
  private readonly definitions = inject(SalaryComponentService);
  private readonly structures = inject(SalaryStructureService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);
  /** Needed because rows are wired up after construction, outside the injection context. */
  private readonly destroyRef = inject(DestroyRef);

  /** Bound from the route. A string, because a URL segment is one. */
  readonly id = input.required<string>();

  readonly form = this.formBuilder.nonNullable.group({
    effectiveFrom: ['', [Validators.required]],
    overrideReason: [''],
    components: this.formBuilder.array<FormGroup>([]),
  });

  readonly employee = signal<EmployeeSummary | null>(null);
  readonly components = signal<SalaryComponentDefinition[]>([]);

  /** The package currently in force, used to seed the form — a raise is the common case. */
  readonly currentPackage = signal<SalaryStructure | null>(null);

  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  /** Server-computed totals for what is currently in the form. */
  readonly totals = signal<StructureTotals | null>(null);

  /** Why the last preview was refused, if it was. */
  readonly previewFailure = signal<ApiFailure | null>(null);

  readonly previewing = signal(false);
  readonly saving = signal(false);
  readonly saveFailure = signal<ApiFailure | null>(null);

  get componentRows(): FormArray<FormGroup> {
    return this.form.controls.components;
  }

  readonly earnings = computed(() =>
    this.components().filter((component) => component.type === 'EARNING'),
  );
  readonly deductions = computed(() =>
    this.components().filter((component) => component.type === 'DEDUCTION'),
  );

  /**
   * The server's account of the missed band, once it has asked for an override reason.
   *
   * Taken from the field error rather than from a band check of our own: the band lives
   * on the employee's grade and the CTC is the server's arithmetic, so duplicating the
   * test here would be a second implementation that could disagree.
   *
   * Held rather than derived from the latest preview, because typing the reason is what
   * makes the next preview succeed. Derived, the box vanished a keystroke or two into
   * the reason — so it is cleared only by a preview that passes *without* a reason,
   * which is the server saying the package is back inside the band.
   */
  private readonly overridePrompt = signal<string | null>(null);

  readonly requiresOverride = computed(() => this.overridePrompt() !== null);

  readonly overrideMessage = this.overridePrompt.asReadonly();

  /** A problem with the chosen components — no BASIC, deductions too high, and so on. */
  readonly componentsMessage = computed(
    () => this.previewFailure()?.messageFor('components') ?? null,
  );

  readonly effectiveFromMessage = computed(
    () =>
      this.previewFailure()?.messageFor('effectiveFrom') ??
      this.saveFailure()?.messageFor('effectiveFrom') ??
      null,
  );

  /**
   * Saving is offered only once the server has costed the package.
   *
   * Not merely a convenience: without a successful preview there is no evidence the
   * assignment would be accepted, and the point of FR-4.5 is that nobody commits to a
   * package sight unseen.
   */
  readonly canSave = computed(
    () => this.totals() !== null && !this.saving() && this.form.controls.effectiveFrom.valid,
  );

  constructor() {
    // Loads the three things the form needs, in parallel.
    toObservable(this.id)
      .pipe(
        switchMap((rawId) => {
          const employeeId = Number(rawId);
          if (!Number.isInteger(employeeId) || employeeId <= 0) {
            this.loadError.set('That is not a valid employee reference.');
            this.loading.set(false);
            return of(null);
          }
          this.loading.set(true);
          this.loadError.set(null);
          return forkJoin({
            employee: this.employees.get(employeeId),
            components: this.definitions.list(),
            history: this.structures.history(employeeId),
          });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (loaded) => {
          if (!loaded) {
            return;
          }
          this.employee.set(loaded.employee);
          this.components.set(loaded.components);
          this.currentPackage.set(loaded.history.find((revision) => revision.current) ?? null);
          this.seedForm(loaded.components, this.currentPackage());
          this.loading.set(false);
        },
        error: (failure: unknown) => {
          this.loadError.set(
            failure instanceof ApiFailure
              ? failure.message
              : 'This assignment form could not be loaded.',
          );
          this.loading.set(false);
        },
      });

    // Every edit re-costs the package against the server.
    this.form.valueChanges
      .pipe(debounceTime(PREVIEW_DEBOUNCE_MS), takeUntilDestroyed())
      .subscribe(() => this.preview());
  }

  /**
   * Fills the form from the package in force, falling back to each component's default.
   *
   * Seeding from the current package is what makes a raise a small edit rather than a
   * re-entry of six figures. The effective date is left blank on purpose — it is the one
   * field that must be a decision, and defaulting it to today would invite an accidental
   * mid-month revision.
   */
  private seedForm(
    definitions: SalaryComponentDefinition[],
    current: SalaryStructure | null,
  ): void {
    const inForce = new Map<number, string>();
    for (const line of [...(current?.earnings ?? []), ...(current?.deductions ?? [])]) {
      inForce.set(line.componentId, line.configuredValue);
    }

    this.componentRows.clear();
    for (const definition of definitions) {
      const existing = inForce.get(definition.id);
      const row = this.formBuilder.nonNullable.group({
        componentId: [definition.id],
        // With no package yet, start from BASIC alone: the form should not imply that
        // every defined component belongs in every package.
        included: [current ? existing !== undefined : definition.code === BASIC_CODE],
        value: [existing ?? definition.value],
      });
      this.bindIncludedToValue(row);
      this.componentRows.push(row);
    }
  }

  /**
   * Keeps a row's value box disabled while the row is not included.
   *
   * Done through the form API rather than a `disabled` attribute in the template: binding
   * that attribute alongside `formControlName` is the case Angular warns about, because
   * the form model and the DOM then hold different ideas of the control's state.
   *
   * A disabled control is excluded from `form.value` but not from `getRawValue()`, which
   * is what the request builder reads — so it still filters on `included` rather than
   * relying on this.
   */
  private bindIncludedToValue(row: FormGroup): void {
    const included = row.controls['included'];
    const value = row.controls['value'];

    if (!included.value) {
      // Silent, so seeding the form does not look like an edit and trigger a preview.
      value.disable({ emitEvent: false });
    }

    included.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((isIncluded) => {
      // Emitting here is deliberate: including or excluding a component changes the
      // package, so it should re-cost.
      if (isIncluded) {
        value.enable();
      } else {
        value.disable();
      }
    });
  }

  private rows(): ComponentRow[] {
    return this.componentRows.controls.map((row) => row.getRawValue() as ComponentRow);
  }

  rowFor(componentId: number): FormGroup {
    const row = this.componentRows.controls.find(
      (candidate) => (candidate.getRawValue() as ComponentRow).componentId === componentId,
    );
    if (!row) {
      throw new Error(`no form row for component ${componentId}`);
    }
    return row;
  }

  isIncluded(componentId: number): boolean {
    return (this.rowFor(componentId).getRawValue() as ComponentRow).included;
  }

  /** The request the form currently describes, or null if it is not yet askable. */
  private currentRequest(): AssignSalaryStructureRequest | null {
    const { effectiveFrom, overrideReason } = this.form.getRawValue();
    if (!effectiveFrom) {
      return null;
    }
    const components: ComponentAssignment[] = this.rows()
      .filter((row) => row.included && row.value.trim() !== '')
      .map((row) => ({ componentId: row.componentId, value: row.value.trim() }));

    if (components.length === 0) {
      return null;
    }
    const reason = overrideReason.trim();
    return {
      effectiveFrom,
      components,
      ...(reason ? { overrideReason: reason } : {}),
    };
  }

  /** Asks the server what the package comes to, and what is wrong with it. */
  private preview(): void {
    const employee = this.employee();
    const request = this.currentRequest();
    if (!employee || !request) {
      this.totals.set(null);
      return;
    }

    this.previewing.set(true);
    this.structures.preview(employee.id, request).subscribe({
      next: (totals) => {
        this.totals.set(totals);
        this.previewFailure.set(null);
        if (!request.overrideReason) {
          this.overridePrompt.set(null);
        }
        this.previewing.set(false);
      },
      error: (failure: unknown) => {
        // The totals are cleared rather than left stale: a figure beside a rejection is
        // worse than no figure, because it looks like the package is costed and fine.
        this.totals.set(null);
        this.previewFailure.set(failure instanceof ApiFailure ? failure : null);
        const bandMissed = this.previewFailure()?.messageFor('overrideReason');
        if (bandMissed !== undefined) {
          this.overridePrompt.set(bandMissed);
        }
        this.previewing.set(false);
      },
    });
  }

  submit(): void {
    const employee = this.employee();
    const request = this.currentRequest();
    this.form.markAllAsTouched();
    if (!employee || !request || this.saving() || !this.canSave()) {
      return;
    }

    this.saving.set(true);
    this.saveFailure.set(null);

    this.structures.assign(employee.id, request).subscribe({
      next: () => {
        // Back to the record, where the new package is now the one in force.
        void this.router.navigate(['/employees', employee.id]);
      },
      error: (failure: unknown) => {
        this.saving.set(false);
        this.saveFailure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }

  /** A component's unit, for the input's suffix. */
  unitFor(definition: SalaryComponentDefinition): string {
    return definition.calculationType === 'PERCENT_OF_BASIC' ? '% of basic' : 'per month';
  }

  isBasic(definition: SalaryComponentDefinition): boolean {
    return definition.code === BASIC_CODE;
  }
}
