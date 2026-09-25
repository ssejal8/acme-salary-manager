import { Component, computed, inject, signal } from '@angular/core';
import { ApiFailure } from '../../../core/http/api-error';
import { PageResponse, emptyPage, shownRange } from '../../../shared/page-response';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import {
  AUDIT_ACTIONS,
  AUDIT_ENTITY_TYPES,
  AuditEvent,
  AuditQuery,
} from '../audit.models';
import { AuditService } from '../audit.service';

const PAGE_SIZE = 25;

/**
 * Who changed what, and when (FR-8.2).
 *
 * ADMIN only, enforced by the API and mirrored by the route guard. One page of this spans
 * every feature at once — which salaries changed, who changed them, what the figures were
 * — so it is the strictest read in the system and the one HR does not get.
 *
 * ## What it shows, and what it deliberately does not
 *
 * Each row names the action, the record it touched, the actor and the moment. The context
 * recorded with the action is rendered as key/value pairs rather than as raw JSON, because
 * an operator reading a trail should not have to read a data structure — but it is only
 * ever the small context the writer chose to record. An audit row is not a copy of the
 * record it describes: that would be a second store of salary data with different access
 * rules (NFR-2.7).
 *
 * ## Timestamps are instants here, not calendar dates
 *
 * Everything else in this application formats dates by splitting the string, because a
 * `LocalDate` has no timezone and `new Date("2022-06-01")` would shift it. An audit
 * timestamp is the opposite case: it is a real instant with a zone, so it is formatted in
 * the reader's own timezone with `Intl`, which is the correct tool for exactly this.
 */
@Component({
  selector: 'app-audit-trail',
  imports: [EmptyState],
  templateUrl: './audit-trail.html',
  styleUrl: './audit-trail.scss',
})
export class AuditTrail {
  private readonly audit = inject(AuditService);

  readonly entityTypes = AUDIT_ENTITY_TYPES;
  readonly actions = AUDIT_ACTIONS;

  readonly page = signal<PageResponse<AuditEvent>>(emptyPage(PAGE_SIZE));
  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);

  readonly entityType = signal<string>('');
  readonly action = signal<string>('');
  readonly from = signal<string>('');
  readonly to = signal<string>('');

  readonly range = computed(() => shownRange(this.page()));

  constructor() {
    this.load(0);
  }

  private query(page: number): AuditQuery {
    return {
      ...(this.entityType() ? { entityType: this.entityType() } : {}),
      ...(this.action() ? { action: this.action() } : {}),
      ...(this.from() ? { from: this.from() } : {}),
      ...(this.to() ? { to: this.to() } : {}),
      page,
      size: PAGE_SIZE,
    };
  }

  load(page: number): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.audit.search(this.query(page)).subscribe({
      next: (found) => {
        this.page.set(found);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.errorMessage.set(
          failure instanceof ApiFailure
            ? failure.message
            : 'The audit trail could not be loaded.',
        );
        this.loading.set(false);
      },
    });
  }

  /** Any filter change starts again at the first page. */
  setEntityType(value: string): void {
    this.entityType.set(value);
    this.load(0);
  }

  setAction(value: string): void {
    this.action.set(value);
    this.load(0);
  }

  setFrom(value: string): void {
    this.from.set(value);
    this.load(0);
  }

  setTo(value: string): void {
    this.to.set(value);
    this.load(0);
  }

  clearFilters(): void {
    this.entityType.set('');
    this.action.set('');
    this.from.set('');
    this.to.set('');
    this.load(0);
  }

  /**
   * An action name as a sentence: `PAYROLL_RUN_FINALISED` reads "Payroll run finalised".
   *
   * Derived rather than mapped, so an action added on the server appears here readably
   * instead of as a missing entry in a lookup table.
   */
  readable(action: string): string {
    const words = action.toLowerCase().replace(/_/g, ' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
  }

  /**
   * An instant in the reader's timezone.
   *
   * `Intl` is right here and wrong elsewhere in this application: this value is a real
   * instant with a zone, not a calendar date, so converting it to local time is the
   * correct thing to do rather than the bug `shared/dates.ts` exists to avoid.
   */
  occurredAt(event: AuditEvent): string {
    const when = new Date(event.occurredAt);
    return Number.isNaN(when.getTime())
      ? event.occurredAt
      : new Intl.DateTimeFormat(undefined, {
          dateStyle: 'medium',
          timeStyle: 'short',
        }).format(when);
  }

  /** The recorded context as pairs, so the table shows facts rather than JSON. */
  detailPairs(event: AuditEvent): { key: string; value: string }[] {
    if (!event.details) {
      return [];
    }
    return Object.entries(event.details).map(([key, value]) => ({
      key: this.readable(key.replace(/([A-Z])/g, '_$1')),
      value: String(value),
    }));
  }

  /** Who did it: the address if the login still exists, the id if it does not. */
  actor(event: AuditEvent): string {
    if (event.actorEmail) {
      return event.actorEmail;
    }
    if (event.actorUserId !== undefined) {
      // The trail outlives the accounts in it, which is why the id is recorded too.
      return `User #${event.actorUserId}`;
    }
    return 'System';
  }
}
