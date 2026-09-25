import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { LoadingService } from '../http/loading.service';

interface NavItem {
  readonly path: string;
  readonly label: string;
  readonly available: boolean;
}

/**
 * The application frame: header, role-aware navigation, and the routed outlet.
 *
 * Wraps every authenticated route, so the header is not re-created on navigation and the
 * progress indicator is not torn down mid-request.
 *
 * The menu hides what a role cannot use. That is a courtesy and nothing more — the API
 * authorises every request independently (NFR-2.2), so this is about not offering someone
 * a screen that would only fill with 403s.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly auth = inject(AuthService);
  private readonly loading = inject(LoadingService);

  readonly currentUser = this.auth.currentUser;
  readonly isLoading = this.loading.isLoading;

  /** Drives the mobile disclosure; ignored from the `sm` breakpoint up. */
  readonly navExpanded = signal(false);

  readonly navItems = computed<NavItem[]>(() => [
    {
      // First, and available to everyone: for an EMPLOYEE it is the only thing here, and
      // for anyone on the payroll it is what they check most often.
      path: '/payslips',
      label: 'My payslips',
      available: true,
    },
    {
      path: '/employees',
      label: 'Employees',
      available: this.auth.canManageEmployees(),
    },
    {
      // The same ADMIN/HR pair as the employee screens, which is what the payroll-run
      // endpoints authorise. Points at the list rather than at the start form: the
      // common visit is to check or finish a run, not to begin another one.
      path: '/payroll-runs',
      label: 'Payroll',
      available: this.auth.canManageEmployees(),
    },
    {
      // FR-6.5: the HR-facing list across every employee, which with a period chosen is
      // the payroll register (FR-7.1). Distinct from "My payslips" above, which is
      // everybody's own.
      path: '/payslips/all',
      label: 'All payslips',
      available: this.auth.canManageEmployees(),
    },
    {
      path: '/reports/compensation',
      label: 'Compensation',
      available: this.auth.canManageEmployees(),
    },
    {
      // ADMIN only: the lists every other screen's dropdowns are built from.
      path: '/reference-data',
      label: 'Reference data',
      available: this.auth.hasAnyRole('ADMIN'),
    },
    {
      // ADMIN only, matching the endpoint — the one area HR cannot reach at all.
      path: '/audit',
      label: 'Audit trail',
      available: this.auth.hasAnyRole('ADMIN'),
    },
    {
      // Reading definitions is ADMIN/HR; only ADMIN may add one, which the screen itself
      // reflects. Hiding the whole area from HR would hide the vocabulary their own
      // packages are built from.
      path: '/salary-components',
      label: 'Components',
      available: this.auth.canManageEmployees(),
    },
  ]);

  readonly isAdmin = computed(() => this.auth.hasAnyRole('ADMIN'));

  readonly visibleNavItems = computed(() => this.navItems().filter((item) => item.available));

  toggleNav(): void {
    this.navExpanded.update((expanded) => !expanded);
  }

  collapseNav(): void {
    this.navExpanded.set(false);
  }

  signOut(): void {
    this.auth.logout();
  }
}
