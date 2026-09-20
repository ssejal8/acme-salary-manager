import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ApiFailure } from '../../../core/http/api-error';
import { CURRENCY_SYMBOL } from '../../../shared/money';
import { CompensationGroup, CompensationMetrics, CompensationOverview } from '../compensation.models';
import { CompensationService } from '../compensation.service';
import { CompensationDashboard } from './compensation-dashboard';

/** The figures the project README publishes for the dev seed. */
function organisation(overrides: Partial<CompensationMetrics> = {}): CompensationMetrics {
  return {
    headcount: 11,
    employeesWithPackage: 9,
    employeesWithoutPackage: 2,
    totalMonthlyGross: '950000.00',
    totalMonthlyDeductions: '58800.00',
    totalMonthlyNet: '891200.00',
    totalAnnualCtc: '11400000.00',
    averageMonthlyGross: '105555.56',
    medianMonthlyGross: '90000.00',
    lowestMonthlyGross: '30000.00',
    highestMonthlyGross: '150000.00',
    ...overrides,
  };
}

function group(
  groupId: number,
  groupName: string,
  gross: string,
  share: string,
): CompensationGroup {
  return {
    groupId,
    groupName,
    shareOfMonthlyGross: share,
    metrics: organisation({
      headcount: 4,
      employeesWithPackage: 4,
      employeesWithoutPackage: 0,
      totalMonthlyGross: gross,
      totalMonthlyNet: gross,
    }),
  };
}

function overview(overrides: Partial<CompensationOverview> = {}): CompensationOverview {
  return {
    generatedAt: '2026-09-16T09:00:00Z',
    organisation: organisation(),
    byDepartment: [
      group(1, 'Engineering', '580000.00', '61.05'),
      group(3, 'Finance', '140000.00', '14.74'),
      group(4, 'Sales', '140000.00', '14.74'),
      group(2, 'HR', '90000.00', '9.47'),
    ],
    byGrade: [group(30, 'G3', '600000.00', '63.16'), group(20, 'G2', '350000.00', '36.84')],
    ...overrides,
  };
}

describe('CompensationDashboard', () => {
  let fixture: ComponentFixture<CompensationDashboard>;
  let component: CompensationDashboard;
  let overviewResult: () => Observable<CompensationOverview>;
  let requests: number;

  beforeEach(() => {
    requests = 0;
    overviewResult = () => of(overview());

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: CompensationService,
          useValue: {
            overview: () => {
              requests++;
              return overviewResult();
            },
          },
        },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(CompensationDashboard);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function queryAll(selector: string): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(selector));
  }

  it('asks for the combined overview once, not the three endpoints separately', async () => {
    // A department share computed a second after the total it is a share of would not add
    // up. One request, one consistent snapshot.
    await createComponent();

    expect(requests).toBe(1);
  });

  describe('the organisation figures', () => {
    it('leads with the total monthly gross', async () => {
      await createComponent();

      expect(text()).toContain('Total monthly gross');
      expect(text()).toContain('₹950,000.00');
    });

    it('shows the remaining totals', async () => {
      await createComponent();

      expect(text()).toContain('₹58,800.00');
      expect(text()).toContain('₹891,200.00');
      expect(text()).toContain('₹11,400,000.00');
    });

    it('shows the distribution, with the median beside the average', async () => {
      await createComponent();

      expect(text()).toContain('₹105,555.56');
      expect(text()).toContain('₹90,000.00');
      expect(text()).toContain('₹30,000.00');
      expect(text()).toContain('₹150,000.00');
      expect(text()).toContain('skew a mean');
    });

    it('does not use tabular figures on the hero number', async () => {
      // Equal-width digits are for aligning a column; on a large standalone figure they
      // read as mechanically spaced.
      await createComponent();

      const hero = queryAll('.hero__value')[0];
      expect(hero).toBeDefined();
      expect(hero.classList.contains('numeric')).toBe(false);
    });

    it('says plainly that this is not a payroll register', async () => {
      // A figure labelled "monthly cost" invites being read as a payroll total, and this
      // one knows nothing about attendance or loss of pay.
      await createComponent();

      expect(text()).toContain('not a payroll register');
      expect(text()).toContain('loss of pay');
    });
  });

  describe('the coverage gap', () => {
    it('is surfaced as a status, with words and not just colour', async () => {
      await createComponent();

      expect(component.coverageGap()).toBe(2);
      expect(text()).toContain('Coverage gap');
      expect(text()).toContain('no compensation package');
      expect(queryAll('.alert--warning').length).toBe(1);
    });

    it('is silent when everyone is priced', async () => {
      overviewResult = () =>
        of(
          overview({
            organisation: organisation({ employeesWithPackage: 11, employeesWithoutPackage: 0 }),
          }),
        );
      await createComponent();

      expect(component.coverageGap()).toBe(0);
      expect(text()).not.toContain('Coverage gap');
    });

    it('reads correctly for a single employee', async () => {
      overviewResult = () =>
        of({ ...overview(), organisation: organisation({ employeesWithoutPackage: 1 }) });
      await createComponent();

      expect(text()).toContain('1 active employee has');
    });
  });

  describe('the breakdowns', () => {
    it('lists departments and grades in the order the server sent', async () => {
      // Most expensive first is the server's ordering; re-sorting here would fight it.
      await createComponent();

      expect(component.byDepartment().map((g) => g.groupName)).toEqual([
        'Engineering',
        'Finance',
        'Sales',
        'HR',
      ]);
      expect(component.byGrade().map((g) => g.groupName)).toEqual(['G3', 'G2']);
    });

    it('shows each row as figures, not only as a bar', async () => {
      // A bar is comparable; a number is readable. Money needs both.
      await createComponent();

      expect(text()).toContain('Engineering');
      expect(text()).toContain('₹580,000.00');
      expect(text()).toContain('61.05%');
      expect(text()).toContain('4 of 4');
    });

    it('draws a bar per row in both breakdowns', async () => {
      await createComponent();

      expect(queryAll('.bar__fill')).toHaveLength(6);
    });

    it('sizes each bar by its share', async () => {
      await createComponent();

      const widths = queryAll('.bar__fill').map((bar) => bar.style.width);
      expect(widths[0]).toBe('61.05%');
      expect(widths[3]).toBe('9.47%');
    });

    it('paints every bar the same hue', async () => {
      // Shading each bar by its own value would be a value-ramp on nominal categories:
      // the category is already named in the row, so it would encode the same number
      // twice. One class, one colour.
      await createComponent();

      for (const bar of queryAll('.bar__fill')) {
        expect(bar.className).toBe('bar__fill');
        expect(bar.style.backgroundColor).toBe('');
      }
    });

    it('offers more on hover than it shows in the row', async () => {
      await createComponent();

      const title = queryAll('.bar')[0].getAttribute('title') ?? '';
      expect(title).toContain('Engineering');
      expect(title).toContain('annual CTC');
    });

    it('never renders a share with a currency symbol', async () => {
      await createComponent();

      for (const value of queryAll('.bar__value')) {
        expect(value.textContent ?? '').not.toContain(CURRENCY_SYMBOL);
      }
    });

    it('clamps a share that could not be read into a sane bar width', async () => {
      overviewResult = () =>
        of(
          overview({
            byDepartment: [
              group(1, 'Engineering', '580000.00', 'not-a-number'),
              group(2, 'HR', '90000.00', '250.00'),
            ],
            byGrade: [],
          }),
        );
      await createComponent();

      const widths = queryAll('.bar__fill').map((bar) => bar.style.width);
      expect(widths[0]).toBe('0%');
      expect(widths[1]).toBe('100%');
    });

    it('omits a breakdown with no rows rather than showing an empty table', async () => {
      overviewResult = () => of(overview({ byGrade: [] }));
      await createComponent();

      expect(text()).toContain('By department');
      expect(text()).not.toContain('By grade');
    });
  });

  describe('when nothing is priced', () => {
    it('says there is nothing to price', async () => {
      overviewResult = () =>
        of(
          overview({
            organisation: organisation({
              headcount: 3,
              employeesWithPackage: 0,
              employeesWithoutPackage: 3,
              totalMonthlyGross: '0.00',
            }),
            byDepartment: [],
            byGrade: [],
          }),
        );
      await createComponent();

      expect(component.hasNoData()).toBe(true);
    });
  });

  describe('when the report cannot be loaded', () => {
    it("shows the server's message", async () => {
      overviewResult = () =>
        throwError(() => new ApiFailure(403, 'You are not permitted to perform this action'));
      await createComponent();

      expect(component.errorMessage()).toBe('You are not permitted to perform this action');
      expect(text()).toContain('You are not permitted to perform this action');
    });

    it('announces the failure and shows no figures', async () => {
      overviewResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();

      expect(queryAll('[role="alert"]').length).toBeGreaterThan(0);
      expect(component.organisation()).toBeNull();
      expect(text()).not.toContain('Total monthly gross');
    });

    it('does not also claim there is nothing to price', async () => {
      overviewResult = () => throwError(() => new ApiFailure(500, 'Something went wrong'));
      await createComponent();

      expect(component.hasNoData()).toBe(false);
    });

    it('falls back to a generic message for a failure it cannot read', async () => {
      overviewResult = () => throwError(() => new Error('socket hang up'));
      await createComponent();

      expect(component.errorMessage()).toBe('The compensation report could not be loaded.');
    });
  });
});
