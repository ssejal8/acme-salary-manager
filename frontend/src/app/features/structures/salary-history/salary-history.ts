import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { IsoDatePipe } from '../../../shared/dates';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { MoneyPipe, PercentagePipe } from '../../../shared/money.pipe';
import { SalaryStructure, SalaryStructureComponent } from '../salary-structure.models';
import { SalaryStructureService } from '../salary-structure.service';

/**
 * An employee's compensation: the package in force, and every revision behind it.
 *
 * Its own component rather than part of the employee detail template, because it is a
 * separate concern on a separate endpoint with a separate failure mode — losing
 * compensation should not cost the user the employee's record, and the parent renders this
 * beside the identity data rather than instead of it.
 *
 * ## Why the arithmetic is shown rather than summarised
 *
 * Each table carries its own total, and the totals come from the server. That is not
 * decoration: amounts are rounded **per component** before being summed (NFR-3.3), which
 * is what guarantees the displayed lines add up to the displayed total exactly. Showing
 * the lines and the total together is what makes that checkable by hand — the property
 * ADR-006 gives up a little arithmetic purity to buy.
 *
 * Nothing is computed here. Every figure on this screen was computed by the server and is
 * rendered as the string it arrived as.
 */
@Component({
  selector: 'app-salary-history',
  imports: [
    DatePipe,
    NgTemplateOutlet,
    RouterLink,
    IsoDatePipe,
    MoneyPipe,
    PercentagePipe,
    EmptyState,
  ],
  templateUrl: './salary-history.html',
  styleUrl: './salary-history.scss',
})
export class SalaryHistory {
  private readonly structures = inject(SalaryStructureService);

  /**
   * Whose compensation to show.
   *
   * A number, not the route's raw string: the parent has already resolved a real employee
   * by the time this renders, so there is no id to validate here.
   */
  readonly employeeId = input.required<number>();

  /**
   * Whether assigning a new package is offered.
   *
   * The parent decides, because it knows the employment status and this component does
   * not. A leaver's compensation cannot be changed at all — the server refuses it (FR-4.6)
   * — so offering the form would be offering a dead end.
   */
  readonly canAssign = input<boolean>(true);

  readonly errorMessage = signal<string | null>(null);

  /** False until the first response, so "no package" is not shown while loading. */
  readonly loaded = signal(false);

  private readonly revisions = toSignal(
    toObservable(this.employeeId).pipe(
      tap(() => {
        this.errorMessage.set(null);
        this.loaded.set(false);
      }),
      switchMap((employeeId) =>
        this.structures.history(employeeId).pipe(
          tap(() => this.loaded.set(true)),
          catchError((failure: unknown) => {
            this.errorMessage.set(
              failure instanceof ApiFailure
                ? failure.message
                : 'This compensation history could not be loaded.',
            );
            this.loaded.set(true);
            return of([] as SalaryStructure[]);
          }),
        ),
      ),
    ),
    { initialValue: [] as SalaryStructure[] },
  );

  /**
   * The package in force, or null when none has been assigned.
   *
   * Read from the server's `current` flag rather than inferred from a missing
   * `supersededOn`. Both would work today; trusting the flag means this screen cannot
   * disagree with the API about which revision is live.
   */
  readonly current = computed(() => this.revisions().find((revision) => revision.current) ?? null);

  /** Earlier revisions, newest first, as the API already ordered them. */
  readonly superseded = computed(() => this.revisions().filter((revision) => !revision.current));

  /**
   * True only once loaded and genuinely empty.
   *
   * Distinguished from an error, because they call for different things: no package is a
   * coverage gap somebody should fill, while a failed request is something to retry.
   */
  readonly hasNoPackage = computed(
    () => this.loaded() && this.errorMessage() === null && this.revisions().length === 0,
  );

  /** Which earlier revisions the user has opened. */
  private readonly expanded = signal<ReadonlySet<number>>(new Set());

  isExpanded(revisionId: number): boolean {
    return this.expanded().has(revisionId);
  }

  toggle(revisionId: number): void {
    this.expanded.update((open) => {
      // A new Set rather than a mutation, so the signal actually notifies.
      const next = new Set(open);
      if (!next.delete(revisionId)) {
        next.add(revisionId);
      }
      return next;
    });
  }

  /**
   * How a component's amount is arrived at, in words.
   *
   * This is the reason the API sends both `configuredValue` and `monthlyAmount`: for a
   * percentage component they differ, and a reviewer needs to see the 12% as well as the
   * ₹5,400 it produces. For a flat component the two are the same figure, so repeating it
   * would be noise.
   */
  basisOf(component: SalaryStructureComponent): string {
    return component.calculationType === 'PERCENT_OF_BASIC' ? 'of basic' : 'Fixed monthly';
  }

  isPercentage(component: SalaryStructureComponent): boolean {
    return component.calculationType === 'PERCENT_OF_BASIC';
  }
}
