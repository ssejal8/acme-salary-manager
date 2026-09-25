import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { PageResponse, emptyPage } from '../../../shared/page-response';
import { AuditEvent, AuditQuery } from '../audit.models';
import { AuditService } from '../audit.service';
import { AuditTrail } from './audit-trail';

function event(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: 1,
    occurredAt: '2026-09-14T10:00:00Z',
    actorUserId: 2,
    actorEmail: 'hr@acme.test',
    entityType: 'PayrollRun',
    entityId: 7,
    action: 'PAYROLL_RUN_FINALISED',
    details: { period: '2026-08', employeeCount: 9959 },
    ...overrides,
  };
}

function page(events: AuditEvent[], overrides: Partial<PageResponse<AuditEvent>> = {}) {
  return {
    content: events,
    page: 0,
    size: 25,
    totalElements: events.length,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
    ...overrides,
  };
}

describe('AuditTrail', () => {
  let fixture: ComponentFixture<AuditTrail>;
  let component: AuditTrail;

  let queries: AuditQuery[];
  let searchResult: () => Observable<PageResponse<AuditEvent>>;

  beforeEach(() => {
    queries = [];
    searchResult = () => of(page([event()]));

    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuditService,
          useValue: {
            search: (query: AuditQuery) => {
              queries.push(query);
              return searchResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(AuditTrail);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function latest(): AuditQuery {
    return queries[queries.length - 1];
  }

  it('asks for the first page with no filters', async () => {
    await createComponent();

    expect(latest()).toEqual({ page: 0, size: 25 });
  });

  it('shows the action, the record, the actor and the context', async () => {
    await createComponent();

    expect(text()).toContain('Payroll run finalised');
    expect(text()).toContain('Payroll run');
    expect(text()).toContain('#7');
    expect(text()).toContain('hr@acme.test');
    expect(text()).toContain('2026-08');
    expect(text()).toContain('9959');
  });

  it('reads an action name as a sentence rather than as a constant', async () => {
    // Derived, not mapped: an action added on the server reads correctly here instead of
    // showing up as a missing lookup entry.
    await createComponent();

    expect(component.readable('EMPLOYEE_DEACTIVATED')).toBe('Employee deactivated');
    expect(component.readable('SALARY_STRUCTURE_SUPERSEDED')).toBe('Salary structure superseded');
  });

  describe('who did it', () => {
    it('names the actor by their login address', async () => {
      await createComponent();

      expect(component.actor(event())).toBe('hr@acme.test');
    });

    it('falls back to the id when the login no longer exists', async () => {
      // The trail outlives the accounts in it, which is why the id is recorded too.
      await createComponent();

      expect(component.actor(event({ actorEmail: undefined, actorUserId: 99 }))).toBe('User #99');
    });

    it('says System when there was no actor at all', async () => {
      await createComponent();

      expect(component.actor(event({ actorEmail: undefined, actorUserId: undefined })))
          .toBe('System');
    });
  });

  describe('timestamps', () => {
    it('formats an instant in the reader timezone', async () => {
      // The opposite case from the rest of this application: an audit timestamp is a real
      // instant with a zone, so converting it to local time is correct rather than the
      // bug shared/dates.ts exists to avoid.
      await createComponent();

      const shown = component.occurredAt(event());
      expect(shown).not.toBe('2026-09-14T10:00:00Z');
      expect(shown).toContain('2026');
    });

    it('shows an unparseable timestamp unchanged rather than "Invalid Date"', async () => {
      await createComponent();

      expect(component.occurredAt(event({ occurredAt: 'not a date' }))).toBe('not a date');
    });
  });

  describe('filtering', () => {
    it('sends the record type', async () => {
      await createComponent();

      component.setEntityType('Employee');
      await settle();

      expect(latest().entityType).toBe('Employee');
    });

    it('sends the action', async () => {
      await createComponent();

      component.setAction('EMPLOYEE_DEACTIVATED');
      await settle();

      expect(latest().action).toBe('EMPLOYEE_DEACTIVATED');
    });

    it('sends both ends of a date range', async () => {
      await createComponent();

      component.setFrom('2026-09-01');
      component.setTo('2026-09-30');
      await settle();

      expect(latest()).toMatchObject({ from: '2026-09-01', to: '2026-09-30' });
    });

    it('omits a filter rather than sending it blank', async () => {
      // `?action=` would be a search for the empty action, not for any action.
      await createComponent();

      component.setAction('EMPLOYEE_CREATED');
      component.setAction('');
      await settle();

      expect(latest().action).toBeUndefined();
    });

    it('goes back to the first page whenever a filter changes', async () => {
      searchResult = () => of(page([event()], { page: 2, hasPrevious: true, totalPages: 5 }));
      await createComponent();

      component.load(2);
      await settle();
      component.setEntityType('PayrollRun');
      await settle();

      expect(latest().page).toBe(0);
    });

    it('clears every filter at once', async () => {
      await createComponent();
      component.setEntityType('Employee');
      component.setAction('EMPLOYEE_CREATED');
      component.setFrom('2026-09-01');
      await settle();

      component.clearFilters();
      await settle();

      expect(latest()).toEqual({ page: 0, size: 25 });
    });
  });

  it('pages on the server', async () => {
    searchResult = () => of(page([event()], { totalElements: 60, totalPages: 3, hasNext: true }));
    await createComponent();

    component.load(1);
    await settle();

    expect(queries.map((query) => query.page)).toEqual([0, 1]);
  });

  it('handles an event that recorded no context', async () => {
    searchResult = () => of(page([event({ details: undefined })]));
    await createComponent();

    expect(component.detailPairs(event({ details: undefined }))).toEqual([]);
    expect(text()).toContain('—');
  });

  it('says so when nothing matches, and why that is not a failure', async () => {
    searchResult = () => of(emptyPage<AuditEvent>());
    await createComponent();

    expect(text()).toContain('Nothing matches');
    expect(text()).toContain('inside the same transaction as the change');
  });

  it("shows the server's message on failure", async () => {
    searchResult = () => throwError(() => new ApiFailure(403, 'You are not permitted'));
    await createComponent();

    expect(component.errorMessage()).toBe('You are not permitted');
    expect(text()).toContain('You are not permitted');
  });

  it('says the trail cannot be edited, including by the reader', async () => {
    await createComponent();

    expect(text()).toContain('Append-only');
  });
});
