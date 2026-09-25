import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import {
  Department,
  Designation,
  Grade,
} from '../../../core/reference-data/reference-data.models';
import { ReferenceDataService } from '../../../core/reference-data/reference-data.service';
import { MoneyPipe } from '../../../shared/money.pipe';

/** Which list is being edited, and which row — null while adding a new one. */
type EditTarget = { list: 'department' | 'designation' | 'grade'; id: number } | null;

/**
 * The vocabulary every employee record is expressed in: departments, designations and
 * grades with their CTC bands (FR-3.1 to FR-3.3).
 *
 * ADMIN only, mirroring the API. Reading these lists is open to HR — a form cannot be
 * filled in without the options it offers — but changing them alters what every record
 * and every report means, which is a different kind of decision.
 *
 * ## Three lists, one screen
 *
 * They are short, closed and always looked at together: choosing a grade band sensibly
 * means seeing the other grades. Three routes would mean three loads and three places to
 * go for one job.
 *
 * ## No deletion, said out loud
 *
 * Employees reference these rows, so the database refuses to remove one in use, and
 * removing an unused one would orphan the history that names it. Rather than offering a
 * button that usually fails, the screen says so.
 */
@Component({
  selector: 'app-reference-data-admin',
  imports: [ReactiveFormsModule, MoneyPipe],
  templateUrl: './reference-data-admin.html',
  styleUrl: './reference-data-admin.scss',
})
export class ReferenceDataAdmin {
  private readonly referenceData = inject(ReferenceDataService);
  private readonly formBuilder = inject(FormBuilder);

  readonly departments = signal<Department[]>([]);
  readonly designations = signal<Designation[]>([]);
  readonly grades = signal<Grade[]>([]);

  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly saving = signal(false);
  readonly failure = signal<ApiFailure | null>(null);
  readonly outcome = signal<string | null>(null);

  /** Which row is being edited, if any. One at a time, across all three lists. */
  readonly editing = signal<EditTarget>(null);

  readonly departmentForm = this.formBuilder.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(20)]],
    name: ['', [Validators.required, Validators.maxLength(120)]],
  });

  readonly designationForm = this.formBuilder.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(120)]],
  });

  readonly gradeForm = this.formBuilder.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(20)]],
    // Strings, because money is a string everywhere in this API (ADR-006) — and blank is
    // meaningful here: it means unbounded on that side, not zero.
    minCtc: [''],
    maxCtc: [''],
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.referenceData.all().subscribe({
      next: (data) => {
        this.departments.set(data.departments);
        this.designations.set(data.designations);
        this.grades.set(data.grades);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.loadError.set(
          failure instanceof ApiFailure ? failure.message : 'Reference data could not be loaded.',
        );
        this.loading.set(false);
      },
    });
  }

  readonly isEditingDepartment = computed(() => this.editing()?.list === 'department');
  readonly isEditingDesignation = computed(() => this.editing()?.list === 'designation');
  readonly isEditingGrade = computed(() => this.editing()?.list === 'grade');

  fieldError(field: string): string | null {
    return this.failure()?.messageFor(field) ?? null;
  }

  /** A refusal that names no field. */
  readonly generalError = computed(() => {
    const failure = this.failure();
    return failure && !failure.hasFieldErrors ? failure.message : null;
  });

  editDepartment(department: Department): void {
    this.reset();
    this.editing.set({ list: 'department', id: department.id });
    // The code is immutable, so it is shown as context rather than as an input.
    this.departmentForm.patchValue({ code: department.code, name: department.name });
    this.departmentForm.controls.code.disable();
  }

  editDesignation(designation: Designation): void {
    this.reset();
    this.editing.set({ list: 'designation', id: designation.id });
    this.designationForm.patchValue({ title: designation.title });
  }

  editGrade(grade: Grade): void {
    this.reset();
    this.editing.set({ list: 'grade', id: grade.id });
    this.gradeForm.patchValue({
      name: grade.name,
      minCtc: grade.minCtc ?? '',
      maxCtc: grade.maxCtc ?? '',
    });
  }

  /** Back to adding rather than editing, with every form cleared. */
  reset(): void {
    this.editing.set(null);
    this.failure.set(null);
    this.outcome.set(null);
    this.departmentForm.reset({ code: '', name: '' });
    this.departmentForm.controls.code.enable();
    this.designationForm.reset({ title: '' });
    this.gradeForm.reset({ name: '', minCtc: '', maxCtc: '' });
  }

  submitDepartment(): void {
    this.departmentForm.markAllAsTouched();
    if (this.departmentForm.invalid || this.saving()) {
      return;
    }
    const { code, name } = this.departmentForm.getRawValue();
    const target = this.editing();
    this.save(
      target?.list === 'department'
        ? this.referenceData.renameDepartment(target.id, name.trim())
        : this.referenceData.createDepartment({ code: code.trim(), name: name.trim() }),
      target?.list === 'department' ? 'Department renamed.' : 'Department added.',
    );
  }

  submitDesignation(): void {
    this.designationForm.markAllAsTouched();
    if (this.designationForm.invalid || this.saving()) {
      return;
    }
    const title = this.designationForm.getRawValue().title.trim();
    const target = this.editing();
    this.save(
      target?.list === 'designation'
        ? this.referenceData.retitleDesignation(target.id, { title })
        : this.referenceData.createDesignation({ title }),
      target?.list === 'designation' ? 'Designation retitled.' : 'Designation added.',
    );
  }

  submitGrade(): void {
    this.gradeForm.markAllAsTouched();
    if (this.gradeForm.invalid || this.saving()) {
      return;
    }
    const { name, minCtc, maxCtc } = this.gradeForm.getRawValue();
    const request = {
      name: name.trim(),
      // Blank becomes null rather than "0": an absent bound is unbounded (FR-4.3).
      minCtc: minCtc.trim() === '' ? null : minCtc.trim(),
      maxCtc: maxCtc.trim() === '' ? null : maxCtc.trim(),
    };
    const target = this.editing();
    this.save(
      target?.list === 'grade'
        ? this.referenceData.updateGrade(target.id, request)
        : this.referenceData.createGrade(request),
      target?.list === 'grade' ? 'Grade amended.' : 'Grade added.',
    );
  }

  /**
   * Sends one write, then refetches all three lists.
   *
   * Refetched rather than patched locally, because the server normalises what it stores —
   * a department code is uppercased, a band is normalised to two decimals — so the list
   * it returns is the truth and a locally spliced row could differ from it.
   */
  private save(request: Observable<unknown>, message: string): void {
    this.saving.set(true);
    this.failure.set(null);
    this.outcome.set(null);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.reset();
        this.outcome.set(message);
        this.load();
      },
      error: (failure: unknown) => {
        this.saving.set(false);
        this.failure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }
}
